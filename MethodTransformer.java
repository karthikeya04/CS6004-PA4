import java.util.*;
import soot.*;
import soot.javaToJimple.LocalGenerator;
import soot.jimple.BinopExpr;
import soot.jimple.CastExpr;
import soot.jimple.Constant;
import soot.jimple.Expr;
import soot.jimple.InstanceOfExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.Jimple;
import soot.jimple.StaticFieldRef;
import soot.jimple.internal.AbstractDefinitionStmt;
import soot.jimple.internal.AbstractInstanceInvokeExpr;
import soot.jimple.internal.JArrayRef;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JBreakpointStmt;
import soot.jimple.internal.JGotoStmt;
import soot.jimple.internal.JIdentityStmt;
import soot.jimple.internal.JIfStmt;
import soot.jimple.internal.JInstanceFieldRef;
import soot.jimple.internal.JInterfaceInvokeExpr;
import soot.jimple.internal.JInvokeStmt;
import soot.jimple.internal.JLookupSwitchStmt;
import soot.jimple.internal.JNewExpr;
import soot.jimple.internal.JNopStmt;
import soot.jimple.internal.JReturnStmt;
import soot.jimple.internal.JReturnVoidStmt;
import soot.jimple.internal.JSpecialInvokeExpr;
import soot.jimple.internal.JTableSwitchStmt;
import soot.jimple.internal.JVirtualInvokeExpr;
import soot.jimple.internal.JimpleLocal;
import soot.jimple.toolkits.callgraph.Edge;
import soot.toolkits.graph.*;
import soot.toolkits.scalar.LiveLocals;
import soot.toolkits.scalar.SimpleLiveLocals;
import soot.util.HashChain;

class MethodTransformer {
    final SootMethod method;
    final LiveLocals liveLocals;
    final Body body;
    final PatchingChain<Unit> units;
    UnitGraph unitGraph; // shouldn't be final because it changes everytime you
                         // inline a method
    PointsToGraph finalPtg;
    final HashMap<Unit, PointsToGraph> inGraph;
    final HashMap<Unit, PointsToGraph> outGraph;
    private HashSet<Unit> workList;
    final private LocalGenerator localGenerator;
    final String methodKey;
    final HashSet<String> nonReplaceableObjs;

    MethodTransformer(SootMethod method) {
        this.method = method;
        body = method.getActiveBody();
        localGenerator = new LocalGenerator(body);
        units = body.getUnits();
        unitGraph = new BriefUnitGraph(body);
        inGraph = new HashMap<Unit, PointsToGraph>();
        outGraph = new HashMap<Unit, PointsToGraph>();
        liveLocals = new SimpleLiveLocals(unitGraph);
        nonReplaceableObjs = new HashSet<String>();
        String className = method.getDeclaringClass().getName();
        String methodName = method.getName();
        methodKey = className + ":" + methodName;
    }

    public void Transform() {
        System.out.println("Analyzing method: " + method.toString());
        RunPointsToAnalysis();
        // System.out.println(body);
        DoScalarReplacement();
        // System.out.println(body);
    }

    private void RunPointsToAnalysis() {
        workList = new HashSet<Unit>(units);
        unitGraph = new BriefUnitGraph(body);
        for (Unit u : inGraph.keySet()) {
            inGraph.put(u, new PointsToGraph());
        }
        for (Unit u : outGraph.keySet()) {
            outGraph.put(u, new PointsToGraph());
        }
        while (!workList.isEmpty()) {
            Unit u = workList.iterator().next();
            workList.remove(u);
            if (!inGraph.containsKey(u)) {
                inGraph.put(u, new PointsToGraph());
            }
            if (!outGraph.containsKey(u)) {
                outGraph.put(u, new PointsToGraph());
            }
            for (Unit pu : unitGraph.getPredsOf(u)) {
                if (outGraph.containsKey(pu))
                    inGraph.get(u).Union(outGraph.get(pu));
            }
            PointsToGraph oldOutGraph = outGraph.get(u).GetDeepCopy();
            ProcessUnit(u, inGraph, outGraph);
            if (!outGraph.get(u).EquivTo(oldOutGraph)) {
                for (Unit su : unitGraph.getSuccsOf(u)) {
                    workList.add(su);
                }
            }
        }
        finalPtg = new PointsToGraph();
        for (Unit u : unitGraph.getTails()) {
            finalPtg.Union(outGraph.get(u));
        }
    }
    private void ProcessUnit(Unit u, HashMap<Unit, PointsToGraph> inGraph,
        HashMap<Unit, PointsToGraph> outGraph) {
        // System.out.println(u.getClass().getName() + " "
        //     + u.getJavaSourceStartLineNumber() + "      |  " + u);
        if (u instanceof JAssignStmt) {
            Value rightOp = ((JAssignStmt) u).getRightOp();
            if (rightOp instanceof InvokeExpr) {
                InvokeExpr invokeExpr = (InvokeExpr) rightOp;
                if (!invokeExpr.getMethodRef().isConstructor()) {
                    if (!invokeExpr.getMethod().isJavaLibraryMethod()) {
                        if (invokeExpr instanceof JVirtualInvokeExpr) {
                            int numTargets = 0;
                            Iterator<Edge> itr =
                                AnalysisTransformer.cg.edgesOutOf(u);
                            while (itr.hasNext()) {
                                numTargets++;
                                itr.next();
                            }
                            // System.out.println(u + " --- " + numTargets);
                            if (numTargets == 1) { // monomorphic callsite
                                SootMethod targetMethod =
                                    AnalysisTransformer.cg.edgesOutOf(u)
                                        .next()
                                        .tgt()
                                        .method();
                                // System.out.print("------------\n");
                                // System.out.println(invokeExpr);
                                // System.out.println(body);
                                // System.out.print("------------\n\n");
                                inlineMethod(u, targetMethod);
                                unitGraph = new BriefUnitGraph(body);
                                return;
                            }
                        }
                    }
                    ProcessAssignLikeUnit(u);
                    PointsToGraph currOutGraph = outGraph.get(u);
                    HashSet<String> escapingObjs = new HashSet<>();
                    for (Value value : invokeExpr.getArgs()) {
                        escapingObjs.addAll(
                            currOutGraph.GetAllPointsToNodes(value, u, false));
                    }
                    if (invokeExpr instanceof JInterfaceInvokeExpr
                        || invokeExpr instanceof JSpecialInvokeExpr
                        || invokeExpr instanceof JVirtualInvokeExpr) {
                        AbstractInstanceInvokeExpr instanceInvokeExpr =
                            (AbstractInstanceInvokeExpr) invokeExpr;
                        Value base = instanceInvokeExpr.getBase();
                        escapingObjs.addAll(
                            currOutGraph.GetAllPointsToNodes(base, u, false));
                    }
                    currOutGraph.MarkAsEscaping(escapingObjs);
                    outGraph.put(u, currOutGraph);

                    return;
                }
            } else {
                ProcessAssignLikeUnit(u);
                return;
            }
        } else if (u instanceof JIdentityStmt) {
            // is semantically like the JAssignStmt and handles
            // assignments of IdentityRef's to make implicit assignments
            // explicit into the StmtGraph.
            ProcessAssignLikeUnit(u);
            return;
        }

        else if (u instanceof JReturnStmt) {
            JReturnStmt returnStmt = (JReturnStmt) u;
            Value op = returnStmt.getOp();
            PointsToGraph currInGraph = inGraph.get(u);
            PointsToGraph currOutGraph = null;
            HashSet<String> nodes =
                currInGraph.GetAllPointsToNodes(op, u, false);
            currOutGraph = currInGraph.GetDeepCopy();
            currOutGraph.MarkAsEscaping(nodes);
            inGraph.put(u, currInGraph);
            outGraph.put(u, currOutGraph);
            return;
        } else if (u instanceof JGotoStmt) {
        } else if (u instanceof JReturnVoidStmt) {
        } else if (u instanceof JTableSwitchStmt) {
        } else if (u instanceof JInvokeStmt) {
            JInvokeStmt jInvokeStmt = (JInvokeStmt) u;
            InvokeExpr invokeExpr = jInvokeStmt.getInvokeExpr();
            if (!invokeExpr.getMethodRef().isConstructor()) {
                // staticInvoke + dynamicInvoke + other instanceInvokes
                if (!invokeExpr.getMethod().isJavaLibraryMethod()) {
                    if (invokeExpr instanceof JVirtualInvokeExpr) {
                        int numTargets = 0;
                        Iterator<Edge> itr =
                            AnalysisTransformer.cg.edgesOutOf(u);
                        while (itr.hasNext()) {
                            numTargets++;
                            itr.next();
                        }
                        if (numTargets == 1) { // monomorphic callsite
                            SootMethod targetMethod =
                                AnalysisTransformer.cg.edgesOutOf(u)
                                    .next()
                                    .tgt()
                                    .method();
                            inlineMethod(u, targetMethod);
                            unitGraph = new BriefUnitGraph(body);
                            // System.out.print("------------");
                            // System.out.println(invokeExpr);
                            // System.out.println(body);
                            // System.out.print("------------");
                            return;
                        }
                    }
                }
                PointsToGraph currInGraph = inGraph.get(u);
                HashSet<String> escapingObjs = new HashSet<>();
                for (Value value : invokeExpr.getArgs()) {
                    escapingObjs.addAll(
                        currInGraph.GetAllPointsToNodes(value, u, false));
                }
                if (invokeExpr instanceof JInterfaceInvokeExpr
                    || invokeExpr instanceof JSpecialInvokeExpr
                    || invokeExpr instanceof JVirtualInvokeExpr) {
                    AbstractInstanceInvokeExpr instanceInvokeExpr =
                        (AbstractInstanceInvokeExpr) invokeExpr;
                    Value base = instanceInvokeExpr.getBase();
                    escapingObjs.addAll(
                        currInGraph.GetAllPointsToNodes(base, u, false));
                }
                PointsToGraph currOutGraph = currInGraph.GetDeepCopy();
                currOutGraph.MarkAsEscaping(escapingObjs);
                inGraph.put(u, currInGraph);
                outGraph.put(u, currOutGraph);
                return;
            }
        } else if (u instanceof JNopStmt) {
        } else if (u instanceof JBreakpointStmt) { // not relevant for
                                                   // static analysis
        } else if (u instanceof JLookupSwitchStmt) { // switch statement
        } else if (u instanceof JIfStmt) {
            // Handle JIfStmt
        }
        outGraph.put(u, inGraph.get(u).GetDeepCopy());
    }

    private void ProcessAssignLikeUnit(
        Unit u) { // for JAssignStmt and JIdentityStmt
        PointsToGraph currInGraph = inGraph.get(u);
        PointsToGraph currOutGraph = null;
        AbstractDefinitionStmt stmt = (AbstractDefinitionStmt) u;
        Value leftOp = stmt.getLeftOp();
        Value rightOp = stmt.getRightOp();
        HashSet<String> lNodes =
            currInGraph.GetAllPointsToNodes(leftOp, u, true);
        HashSet<String> rNodes =
            currInGraph.GetAllPointsToNodes(rightOp, u, false);
        String edge = PointsToGraph.emptyEdge;
        Boolean isStrongUpdate = false;
        if (leftOp instanceof JInstanceFieldRef) {
            JInstanceFieldRef jInstanceFieldRef = (JInstanceFieldRef) leftOp;
            edge = jInstanceFieldRef.getField().toString();
        } else if (leftOp instanceof JArrayRef) {
            edge = "[]";
            // TODO: check, nothing else can add a non-empty edge
        }
        if (!(leftOp instanceof JInstanceFieldRef)
            && !(leftOp instanceof JArrayRef)
            && !(leftOp instanceof StaticFieldRef)) {
            isStrongUpdate = true;
            // TODO: check
        }
        currOutGraph = currInGraph.GetDeepCopy();
        currOutGraph.Connect(lNodes, rNodes, edge, isStrongUpdate);

        // System.out.println(lNodes + " " + rNodes);
        inGraph.put(u, currInGraph);
        outGraph.put(u, currOutGraph);
        // System.out.println(currOutGraph.graph);
    }

    void inlineMethod(Unit u, SootMethod targetMethod) {
        AbstractInstanceInvokeExpr instInvokeExpr;
        Boolean isConstructor = targetMethod.isConstructor();
        if (u instanceof JAssignStmt) {
            Value rightOp = ((JAssignStmt) u).getRightOp();
            if (rightOp instanceof InvokeExpr) {
                InvokeExpr invokeExpr = (InvokeExpr) rightOp;
                if (invokeExpr instanceof AbstractInstanceInvokeExpr) {
                    instInvokeExpr = (AbstractInstanceInvokeExpr) invokeExpr;
                } else {
                    throw new RuntimeException("Not a virtual Invoke");
                }
            } else {
                throw new RuntimeException("Not a virtual Invoke");
            }
        } else if (u instanceof JInvokeStmt) {
            InvokeExpr invokeExpr = ((JInvokeStmt) u).getInvokeExpr();
            if (invokeExpr instanceof AbstractInstanceInvokeExpr) {
                instInvokeExpr = (AbstractInstanceInvokeExpr) invokeExpr;
            } else {
                throw new RuntimeException("Not a virtual Invoke");
            }
        } else {
            throw new RuntimeException("Not an invoke Expression");
        }
        // System.out.println(AnalysisTransformer.Analyses.keySet());
        // System.out.println(targetMethod);
        Body targetBody = null;
        if (isConstructor) {
            targetBody = (Body) AnalysisTransformer.ConstrAnalyses
                             .get(targetMethod.toString())
                             .body.clone();
        } else {
            targetBody =
                (Body) AnalysisTransformer.Analyses.get(targetMethod.toString())
                    .body.clone();
        }
        PatchingChain<Unit> targetUnits = targetBody.getUnits();
        PatchingChain<Unit> newUnits =
            new PatchingChain<Unit>(new HashChain<>());
        HashMap<String, Local> localsMapping = new HashMap<String, Local>();
        List<Local> parameterLocals = targetBody.getParameterLocals();
        List<Value> args = instInvokeExpr.getArgs();
        Local thisLocal = targetBody.getThisLocal();
        Local base = (Local) instInvokeExpr.getBase();
        localsMapping.put(thisLocal.toString(), base);
        for (int i = 0; i < args.size(); i++) {
            Local param = parameterLocals.get(i);
            Value arg = args.get(i);
            Local pLocal = getNewLocal(param.getType());
            // pass by value
            newUnits.add(Jimple.v().newAssignStmt(pLocal, arg));
            localsMapping.put(param.toString(), pLocal);
        }
        for (Local local : targetBody.getLocals()) {
            if (!localsMapping.containsKey(local.toString())) {
                localsMapping.put(
                    local.toString(), getNewLocal(local.getType()));
            }
        }
        // System.out.println(u + " " + method);
        // System.out.println(units);
        Unit uSucc = units.getSuccOf(u);
        HashMap<Unit, Unit> retUnitsMapping = new HashMap<Unit, Unit>();
        for (Unit tu : targetUnits) {
            if (ProcessUnitForRenaming(tu, localsMapping)) {
                newUnits.add(tu);
            }
            if (tu instanceof JReturnStmt) {
                JReturnStmt jReturnStmt = (JReturnStmt) tu;
                Value op = jReturnStmt.getOp();
                if (op instanceof JInstanceFieldRef) { // TODO: handle "return
                                                       // $r0.f" cases
                    JInstanceFieldRef jInstanceFieldRef =
                        (JInstanceFieldRef) op;
                    Unit endUnit;
                    Value rbase = jInstanceFieldRef.getBase();
                    Local rbaseLocal = (Local) rbase;
                    jInstanceFieldRef.setBase(
                        localsMapping.get(rbaseLocal.toString()));
                    Local nlocal = getNewLocal(op.getType());
                    endUnit = Jimple.v().newAssignStmt(nlocal, op);
                    newUnits.add(endUnit);
                    if (u instanceof JAssignStmt) {
                        JAssignStmt jAssignStmt = (JAssignStmt) u;
                        Value leftOp = jAssignStmt.getLeftOp();
                        newUnits.add(Jimple.v().newAssignStmt(leftOp, nlocal));
                    }
                    newUnits.add(Jimple.v().newGotoStmt(uSucc));
                    retUnitsMapping.put(tu, endUnit);
                } else if (op instanceof Local) {
                    Local retLocal = localsMapping.get(op.toString());
                    Unit endUnit;
                    if (u instanceof JAssignStmt) {
                        JAssignStmt jAssignStmt = (JAssignStmt) u;
                        Value leftOp = jAssignStmt.getLeftOp();
                        endUnit = Jimple.v().newAssignStmt(leftOp, retLocal);
                        newUnits.add(endUnit);
                        newUnits.add(Jimple.v().newGotoStmt(uSucc));
                    } else {
                        endUnit = Jimple.v().newGotoStmt(uSucc);
                        newUnits.add(endUnit);
                    }
                    retUnitsMapping.put(tu, endUnit);
                } else if (op instanceof Constant) {
                    Unit endUnit;
                    if (u instanceof JAssignStmt) {
                        JAssignStmt jAssignStmt = (JAssignStmt) u;
                        Value leftOp = jAssignStmt.getLeftOp();
                        endUnit = Jimple.v().newAssignStmt(leftOp, op);
                        newUnits.add(endUnit);
                        newUnits.add(Jimple.v().newGotoStmt(uSucc));
                    } else {
                        endUnit = Jimple.v().newGotoStmt(uSucc);
                        newUnits.add(endUnit);
                    }
                    retUnitsMapping.put(tu, endUnit);
                }
            } else if (tu instanceof JReturnVoidStmt) {
                Unit endUnit = Jimple.v().newGotoStmt(uSucc);
                newUnits.add(endUnit);
                retUnitsMapping.put(tu, endUnit);
            }
        }

        // handle "return" target units in if stmts
        for (Unit tu : targetUnits) {
            if (tu instanceof JIfStmt) {
                JIfStmt jIfStmt = (JIfStmt) tu;
                Unit tgt = jIfStmt.getTarget();
                if (retUnitsMapping.containsKey(tgt)) {
                    jIfStmt.setTarget(retUnitsMapping.get(tgt));
                }
            }
        }

        // handle "return" target units in goto stmts
        for (Unit tu : targetUnits) {
            if (tu instanceof JGotoStmt) {
                JGotoStmt jGotoStmt = (JGotoStmt) tu;
                Unit tgt = jGotoStmt.getTarget();
                if (tgt instanceof JReturnStmt
                    || tgt instanceof JReturnVoidStmt) {
                    if (retUnitsMapping.containsKey(tgt)) {
                        jGotoStmt.setTarget(retUnitsMapping.get(tgt));
                    }
                }
            }
        }
        // remove u from units and add all new units to the worklist & units
        units.insertAfter(newUnits, u);
        units.remove(u);
        workList.addAll(newUnits);
    }

    // renames the local using the mapping given and returns true if the
    // unit can be added to the newUnits set Note: It doesn't process return and
    // if statements' target unit (handled in inlineMethod itself)
    static Boolean ProcessUnitForRenaming(
        Unit u, HashMap<String, Local> localsMapping) {
        if (u instanceof JAssignStmt) {
            JAssignStmt jAssignStmt = (JAssignStmt) u;
            Value leftOp = jAssignStmt.getLeftOp();
            Value rightOp = jAssignStmt.getRightOp();
            if (leftOp instanceof Local) {
                Local leftLocal = (Local) leftOp;
                jAssignStmt.setLeftOp(localsMapping.get(leftLocal.toString()));
            } else if (leftOp instanceof JInstanceFieldRef) {
                JInstanceFieldRef jInstanceFieldRef =
                    (JInstanceFieldRef) leftOp;
                Value base = jInstanceFieldRef.getBase();
                if (base instanceof Local) {
                    Local baseLocal = (Local) base;
                    jInstanceFieldRef.setBase(
                        localsMapping.get(baseLocal.toString()));
                }
            }
            // TODO: check if I've handled all the cases

            if (rightOp instanceof Local) {
                Local rightLocal = (Local) rightOp;
                jAssignStmt.setRightOp(
                    localsMapping.get(rightLocal.toString()));
            } else if (rightOp instanceof JInstanceFieldRef) {
                JInstanceFieldRef jInstanceFieldRef =
                    (JInstanceFieldRef) rightOp;
                Value base = jInstanceFieldRef.getBase();
                if (base instanceof Local) {
                    Local baseLocal = (Local) base;
                    jInstanceFieldRef.setBase(
                        localsMapping.get(baseLocal.toString()));
                }
            } else if (rightOp instanceof BinopExpr) {
                BinopExpr binopExpr = (BinopExpr) rightOp;
                Value op1 = binopExpr.getOp1();
                Value op2 = binopExpr.getOp2();
                if (op1 instanceof Local) {
                    Local op1Local = (Local) op1;
                    binopExpr.setOp1(localsMapping.get(op1Local.toString()));
                }
                if (op2 instanceof Local) {
                    Local op2Local = (Local) op2;
                    binopExpr.setOp2(localsMapping.get(op2Local.toString()));
                }
            } else if (rightOp instanceof InvokeExpr) {
                InvokeExpr invokeExpr = (InvokeExpr) rightOp;
                RenameInvokeExpr(invokeExpr, localsMapping);
            } else if (rightOp instanceof CastExpr) {
                CastExpr castExpr = (CastExpr) rightOp;
                Value op = castExpr.getOp();
                if (op instanceof Local) {
                    Local opLocal = (Local) op;
                    castExpr.setOp(localsMapping.get(opLocal.toString()));
                }
            } else if (rightOp instanceof InstanceOfExpr) {
                InstanceOfExpr instanceOfExpr = (InstanceOfExpr) rightOp;
                Value op = instanceOfExpr.getOp();
                if (op instanceof Local) {
                    Local opLocal = (Local) op;
                    instanceOfExpr.setOp(localsMapping.get(opLocal.toString()));
                }
            }

        } else if (u instanceof JBreakpointStmt) {
        } else if (u instanceof JGotoStmt) {
            // JGotoStmt jGotoStmt = (JGotoStmt) u;
            // Unit tgt = jGotoStmt.getTarget();
            // if (tgt instanceof JReturnStmt || tgt instanceof JReturnVoidStmt)
            // {
            //     return false;
            // }
        } else if (u instanceof JIdentityStmt) {
            // $r0 = this, and $r0 = param stmts
            // no need to bother about $r0 = this kind of stmts because it's
            // already handled in the beginning
            return false;
        } else if (u instanceof JIfStmt) {
            JIfStmt jIfStmt = (JIfStmt) u;
            Value condition = jIfStmt.getCondition();
            if (condition instanceof BinopExpr) {
                BinopExpr binopExpr = (BinopExpr) condition;
                Value op1 = binopExpr.getOp1();
                Value op2 = binopExpr.getOp2();
                if (op1 instanceof Local) {
                    Local op1Local = (Local) op1;
                    binopExpr.setOp1(localsMapping.get(op1Local.toString()));
                }
                if (op2 instanceof Local) {
                    Local op2Local = (Local) op2;
                    binopExpr.setOp2(localsMapping.get(op2Local.toString()));
                }
            }
            // target unit is handled in inlineMethod
            // TODO: handle other cases?
        } else if (u instanceof JInvokeStmt) {
            JInvokeStmt jInvokeStmt = (JInvokeStmt) u;
            InvokeExpr invokeExpr = jInvokeStmt.getInvokeExpr();
            RenameInvokeExpr(invokeExpr, localsMapping);
        } else if (u instanceof JNopStmt) {
        } else if (u instanceof JReturnStmt) {
            return false;
        } else if (u instanceof JReturnVoidStmt) {
            return false;
        }
        return true;
    }

    static void RenameInvokeExpr(
        InvokeExpr invokeExpr, HashMap<String, Local> localsMapping) {
        if (invokeExpr instanceof AbstractInstanceInvokeExpr) {
            AbstractInstanceInvokeExpr instInvokeExpr =
                (AbstractInstanceInvokeExpr) invokeExpr;
            Value base = instInvokeExpr.getBase();
            if (base instanceof Local) {
                Local baseLocal = (Local) base;
                instInvokeExpr.setBase(localsMapping.get(baseLocal.toString()));
            }
        }
        List<Value> args = invokeExpr.getArgs();
        for (int i = 0; i < args.size(); i++) {
            Value arg = args.get(i);
            if (arg instanceof Local) {
                Local argLocal = (Local) arg;
                invokeExpr.setArg(i, localsMapping.get(argLocal.toString()));
            }
        }
    }

    private void DoScalarReplacement() {
        HashSet<String> allHeapNodes = finalPtg.GetAllHeapNodes();
        HashSet<String> allEscapingObjs = finalPtg.GetAllEscapingObjs();
        nonReplaceableObjs.addAll(allEscapingObjs);

        for (Unit u : units) {
            if (u instanceof JAssignStmt) {
                JAssignStmt jAssignStmt = (JAssignStmt) u;
                ProcessValue(jAssignStmt.getRightOp(), nonReplaceableObjs);
            } else if (u instanceof JIdentityStmt) {
            } else if (u instanceof JReturnStmt) {
            } else if (u instanceof JGotoStmt) {
            } else if (u instanceof JReturnVoidStmt) {
            } else if (u instanceof JTableSwitchStmt) {
            } else if (u instanceof JInvokeStmt) {
            } else if (u instanceof JNopStmt) {
            } else if (u instanceof JBreakpointStmt) {
            } else if (u instanceof JLookupSwitchStmt) {
            } else if (u instanceof JIfStmt) {
                JIfStmt jIfStmt = (JIfStmt) u;
                Value condition = jIfStmt.getCondition();
                ProcessValue(condition, nonReplaceableObjs);
            }
        }

        for (String node : allHeapNodes) {
            if (!nonReplaceableObjs.contains(node)) {
                // System.out.println(node);
                ScalarReplace(node);
            }
        }
    }

    private void ProcessValue(Value value, HashSet<String> nonReplaceableObjs) {
        if (value instanceof Expr) {
            Expr expr = (Expr) value;
            if (expr instanceof BinopExpr) {
                BinopExpr binopExpr = (BinopExpr) expr;
                Value op1 = binopExpr.getOp1();
                Value op2 = binopExpr.getOp2();
                nonReplaceableObjs.addAll(
                    finalPtg.GetAllPointsToNodes(op1, null, false));
                nonReplaceableObjs.addAll(
                    finalPtg.GetAllPointsToNodes(op2, null, false));
            } else if (expr instanceof CastExpr) {
                CastExpr castExpr = (CastExpr) expr;
                Value op = castExpr.getOp();
                MarkReachableAsNonReplaceable(op, nonReplaceableObjs);
            } else if (expr instanceof InstanceOfExpr) {
                InstanceOfExpr instanceOfExpr = (InstanceOfExpr) expr;
                Value op = instanceOfExpr.getOp();
                MarkReachableAsNonReplaceable(op, nonReplaceableObjs);
            }
        }
    }

    private void ScalarReplace(String node) {
        assert (finalPtg.IsHeapNode(node));
        RefType refType = PointsToGraph.objectTypes.get(node);
        SootClass sootClass = refType.getSootClass();
        Unit instUnit = null; // instantiation unit
        Iterator<Unit> unitItr = units.iterator();
        while (unitItr.hasNext()) {
            Unit u = unitItr.next();
            if (u instanceof JAssignStmt) {
                JAssignStmt jAssignStmt = (JAssignStmt) u;
                Value leftOp = jAssignStmt.getLeftOp();
                Value rightOp = jAssignStmt.getRightOp();
                if (rightOp instanceof JNewExpr) {
                    String objNode =
                        Integer.toString(u.getJavaSourceStartLineNumber());
                    if (objNode.equals(node)) {
                        instUnit = u;
                        break;
                    }
                }
            }
        }
        Unit constrUnit = unitItr.next();
        // System.out.println(constrUnit);
        JInvokeStmt cInvokeStmt = (JInvokeStmt) constrUnit;
        JSpecialInvokeExpr cInvokeExpr =
            (JSpecialInvokeExpr) cInvokeStmt.getInvokeExpr();
        SootMethod targetMethod = cInvokeExpr.getMethod();
        if (!AnalysisTransformer.ConstrAnalyses.containsKey(
                targetMethod.toString())) {
            ConstructorTransformer analysis =
                new ConstructorTransformer(targetMethod);
            analysis.Transform();
            AnalysisTransformer.ConstrAnalyses.put(
                targetMethod.toString(), analysis);
        }
        // System.out.println(body);
        inlineMethod(constrUnit, targetMethod);

        // System.out.println(constrUnit);
        // System.out.println(body);
        RunPointsToAnalysis();
        HashMap<String, Local> fieldMapping = new HashMap<String, Local>();
        for (SootField field : sootClass.getFields()) {
            Local fLocal = getNewLocal(field.getType());
            fieldMapping.put(field.toString(), fLocal);
        }
        List<Unit> toRemove = new ArrayList<Unit>();
        // replacement
        // System.out.println(node);
        for (Unit u : units) {
            if (u instanceof JAssignStmt) {
                JAssignStmt jAssignStmt = (JAssignStmt) u;
                Value leftOp = jAssignStmt.getLeftOp();
                Value rightOp = jAssignStmt.getRightOp();
                Local srl = GetLocalForSR(leftOp, u, node, fieldMapping);
                if (srl != null) {
                    jAssignStmt.setLeftOp(srl);
                }
                srl = GetLocalForSR(rightOp, u, node, fieldMapping);
                if (srl != null) {
                    jAssignStmt.setRightOp(srl);
                }

                if (rightOp instanceof InvokeExpr) {
                    InvokeExpr invokeExpr = (InvokeExpr) rightOp;
                    List<Value> args = invokeExpr.getArgs();
                    for (int i = 0; i < args.size(); i++) {
                        Value arg = args.get(i);
                        srl = GetLocalForSR(arg, u, node, fieldMapping);
                        if (srl != null) {
                            invokeExpr.setArg(i, srl);
                        }
                    }
                }
                if (rightOp instanceof Local) {
                    HashSet<String> objs =
                        finalPtg.GetAllPointsToNodes(rightOp, u, false);
                    if (objs.size() == 1) {
                        String obj = objs.iterator().next();
                        if (!nonReplaceableObjs.contains(obj)) {
                            toRemove.add(u);
                        }
                    }
                }
            } else if (u instanceof JIdentityStmt) {
            } else if (u instanceof JReturnStmt) {
                JReturnStmt jReturnStmt = (JReturnStmt) u;
                Value op = jReturnStmt.getOp();
                Local srl = GetLocalForSR(op, u, node, fieldMapping);
                if (srl != null) {
                    jReturnStmt.setOp(srl);
                }
            } else if (u instanceof JGotoStmt) {
            } else if (u instanceof JReturnVoidStmt) {
            } else if (u instanceof JTableSwitchStmt) {
            } else if (u instanceof JInvokeStmt) {
                JInvokeStmt jInvokeStmt = (JInvokeStmt) u;
                InvokeExpr invokeExpr = jInvokeStmt.getInvokeExpr();
                List<Value> args = invokeExpr.getArgs();
                for (int i = 0; i < args.size(); i++) {
                    Value arg = args.get(i);
                    Local srl = GetLocalForSR(arg, u, node, fieldMapping);
                    if (srl != null) {
                        invokeExpr.setArg(i, srl);
                    }
                }
            } else if (u instanceof JNopStmt) {
            } else if (u instanceof JBreakpointStmt) {
            } else if (u instanceof JLookupSwitchStmt) {
            } else if (u instanceof JIfStmt) {
                JIfStmt jIfStmt = (JIfStmt) u;
                Value condition = jIfStmt.getCondition();
                if (condition instanceof BinopExpr) {
                    BinopExpr binopExpr = (BinopExpr) condition;
                    Value op1 = binopExpr.getOp1();
                    Value op2 = binopExpr.getOp2();
                    Local srl = GetLocalForSR(op1, u, node, fieldMapping);
                    if (srl != null) {
                        binopExpr.setOp1(srl);
                    }
                    srl = GetLocalForSR(op2, u, node, fieldMapping);
                    if (srl != null) {
                        binopExpr.setOp2(srl);
                    }
                }
            }
        }
        for (Unit u : toRemove) {
            units.remove(u);
        }
        units.remove(instUnit);
    }

    private Local GetLocalForSR(
        Value value, Unit u, String node, HashMap<String, Local> fieldMapping) {
        if (value instanceof JInstanceFieldRef) {
            JInstanceFieldRef jInstanceFieldRef = (JInstanceFieldRef) value;
            // System.out.println(u);
            // System.out.println(inGraph.keySet());
            Value base = jInstanceFieldRef.getBase();
            HashSet<String> objs =
                inGraph.get(u).GetAllPointsToNodes(base, u, false);
            if (objs.size() == 1) {
                String obj = objs.iterator().next();
                if (obj.equals(node)) {
                    return fieldMapping.get(
                        jInstanceFieldRef.getField().toString());
                }
            }
        }
        return null;
    }
    private void MarkReachableAsNonReplaceable(
        Value value, HashSet<String> nonReplaceableObjs) {
        // TODO: potential for nullptr exception
        nonReplaceableObjs.addAll(
            finalPtg.GetAllPointsToNodes(value, null, false));
    }

    private Local getNewLocal(Type type) {
        return localGenerator.generateLocal(type);
    }

    public PointsToGraph GetFinalPtg() {
        return finalPtg.GetDeepCopy();
    }
}

// lightweight transformer
// Used only while scalar replacing objects
class ConstructorTransformer {
    final SootMethod method;
    final Body body;
    final PatchingChain<Unit> units;
    UnitGraph unitGraph; // shouldn't be final because it changes everytime you
                         // inline a method
    final private LocalGenerator localGenerator;

    ConstructorTransformer(SootMethod method) {
        this.method = method;
        body = (Body) method.getActiveBody().clone();
        localGenerator = new LocalGenerator(body);
        units = body.getUnits();
        unitGraph = new BriefUnitGraph(body);
    }

    void Transform() {
        Unit javaObjUnit = null;
        Unit reqUnit = null;
        SootMethod targetMethod = null;
        for (Unit u : units) {
            if (u instanceof JInvokeStmt) {
                JInvokeStmt jInvokeStmt = (JInvokeStmt) u;
                if (jInvokeStmt.getInvokeExpr() instanceof JSpecialInvokeExpr) {
                    JSpecialInvokeExpr invokeExpr =
                        (JSpecialInvokeExpr) (jInvokeStmt.getInvokeExpr());
                    targetMethod = invokeExpr.getMethod();
                    if (targetMethod.getDeclaringClass().isApplicationClass()) {
                        if (!AnalysisTransformer.ConstrAnalyses.containsKey(
                                targetMethod.toString())) {
                            ConstructorTransformer analysis =
                                new ConstructorTransformer(targetMethod);
                            analysis.Transform();
                            AnalysisTransformer.ConstrAnalyses.put(
                                targetMethod.toString(), analysis);
                        }
                        reqUnit = u;
                    } else {
                        javaObjUnit = u;
                    }
                    break;
                }
            }
        }
        if (reqUnit != null) {
            inlineConstr(reqUnit, targetMethod);
            unitGraph = new BriefUnitGraph(body);
        }
        if (javaObjUnit != null) {
            units.remove(javaObjUnit);
        }
    }

    void inlineConstr(Unit u, SootMethod targetMethod) {
        JInvokeStmt jInvokeStmt = (JInvokeStmt) u;
        JSpecialInvokeExpr invokeExpr =
            (JSpecialInvokeExpr) (jInvokeStmt.getInvokeExpr());
        Body targetBody = (Body) AnalysisTransformer.ConstrAnalyses
                              .get(targetMethod.toString())
                              .body.clone();

        PatchingChain<Unit> targetUnits = targetBody.getUnits();
        PatchingChain<Unit> newUnits =
            new PatchingChain<Unit>(new HashChain<>());
        HashMap<String, Local> localsMapping = new HashMap<String, Local>();
        List<Local> parameterLocals = targetBody.getParameterLocals();
        List<Value> args = invokeExpr.getArgs();
        Local thisLocal = targetBody.getThisLocal();
        Local base = (Local) invokeExpr.getBase();
        localsMapping.put(thisLocal.toString(), base);
        for (int i = 0; i < args.size(); i++) {
            Local param = parameterLocals.get(i);
            Value arg = args.get(i);
            Local pLocal = getNewLocal(param.getType());
            // pass by value
            newUnits.add(Jimple.v().newAssignStmt(pLocal, arg));
            localsMapping.put(param.toString(), pLocal);
        }
        for (Local local : targetBody.getLocals()) {
            if (!localsMapping.containsKey(local.toString())) {
                localsMapping.put(
                    local.toString(), getNewLocal(local.getType()));
            }
        }
        // System.out.println(u + " " + method);
        // System.out.println(units);
        Unit uSucc = units.getSuccOf(u);
        // System.out.println(" ------ " + uSucc);
        HashMap<Unit, Unit> retUnitsMapping = new HashMap<Unit, Unit>();
        for (Unit tu : targetUnits) {
            if (MethodTransformer.ProcessUnitForRenaming(tu, localsMapping)) {
                newUnits.add(tu);
            }
            if (tu instanceof JReturnVoidStmt) {
                Unit endUnit = Jimple.v().newGotoStmt(uSucc);
                newUnits.add(endUnit);
                retUnitsMapping.put(tu, endUnit);
            }
        }

        // handle "return" target units in if stmts
        for (Unit tu : targetUnits) {
            if (tu instanceof JIfStmt) {
                JIfStmt jIfStmt = (JIfStmt) tu;
                Unit tgt = jIfStmt.getTarget();
                if (retUnitsMapping.containsKey(tgt)) {
                    jIfStmt.setTarget(retUnitsMapping.get(tgt));
                }
            }
        }

        // handle "return" target units in goto stmts
        for (Unit tu : targetUnits) {
            if (tu instanceof JGotoStmt) {
                JGotoStmt jGotoStmt = (JGotoStmt) tu;
                Unit tgt = jGotoStmt.getTarget();
                if (tgt instanceof JReturnStmt
                    || tgt instanceof JReturnVoidStmt) {
                    if (retUnitsMapping.containsKey(tgt)) {
                        jGotoStmt.setTarget(retUnitsMapping.get(tgt));
                    }
                }
            }
        }
        // remove u from units and add all new units to the worklist & units
        units.insertAfter(newUnits, u);
        units.remove(u);
    }

    private Local getNewLocal(Type type) {
        return localGenerator.generateLocal(type);
    }
}