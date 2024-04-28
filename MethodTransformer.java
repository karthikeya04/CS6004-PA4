import java.util.*;
import soot.*;
import soot.javaToJimple.LocalGenerator;
import soot.jimple.BinopExpr;
import soot.jimple.CastExpr;
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
import soot.jimple.internal.JNopStmt;
import soot.jimple.internal.JReturnStmt;
import soot.jimple.internal.JReturnVoidStmt;
import soot.jimple.internal.JSpecialInvokeExpr;
import soot.jimple.internal.JTableSwitchStmt;
import soot.jimple.internal.JVirtualInvokeExpr;
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
    final PointsToGraph finalPtg;
    final HashMap<Unit, PointsToGraph> inGraph;
    final HashMap<Unit, PointsToGraph> outGraph;
    private HashSet<Unit> workList;
    final private LocalGenerator localGenerator;
    final String methodKey;
    int currentlocalIndex = 0;

    MethodTransformer(SootMethod method) {
        this.method = method;
        body = method.getActiveBody();
        localGenerator = new LocalGenerator(body);
        units = body.getUnits();
        unitGraph = new BriefUnitGraph(body);
        inGraph = new HashMap<Unit, PointsToGraph>();
        outGraph = new HashMap<Unit, PointsToGraph>();
        finalPtg = new PointsToGraph();
        liveLocals = new SimpleLiveLocals(unitGraph);
        String className = method.getDeclaringClass().getName();
        String methodName = method.getName();
        methodKey = className + ":" + methodName;
    }

    public void Analyze() {
        workList = new HashSet<Unit>(units);
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
        for (Unit u : unitGraph.getTails()) {
            finalPtg.Union(outGraph.get(u));
        }

        // System.out.println(body);
        // inline virtual methods at monomorphic call sites
        // TODO: figure out how to do this
        // for (Unit u : units) {
        //     if (u instanceof JInvokeStmt) {
        //         JInvokeStmt jInvokeStmt = (JInvokeStmt) u;
        //         InvokeExpr invokeExpr = jInvokeStmt.getInvokeExpr();
        //         if (invokeExpr instanceof JVirtualInvokeExpr) {
        //             JVirtualInvokeExpr jVirtualInvokeExpr =
        //                 (JVirtualInvokeExpr) invokeExpr;
        //             Value base = jVirtualInvokeExpr.getBase();
        //             Local baseLocal = (Local) base;
        //             SootClass baseClass =
        //                 ((RefType) baseLocal.getType()).getSootClass();
        //         }
        //     }
        // }
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
                    // staticInvoke + dynamicInvoke +
                    // other instanceInvokes
                    if (!invokeExpr.getMethod().isJavaLibraryMethod()) {
                        if (invokeExpr instanceof JVirtualInvokeExpr) {
                            int numTargets = 0;
                            Iterator<Edge> itr =
                                AnalysisTransformer.cg.edgesOutOf(u);
                            while (itr.hasNext()) {
                                numTargets++;
                                itr.next();
                            }
                            System.out.println(u + " --- " + numTargets);
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
                        ProcessInvokeExpr(u, invokeExpr);
                        return;
                    }
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
            String retNode = PointsToGraph.GetReturnNode();
            if (!currOutGraph.graph.containsKey(retNode)) {
                currOutGraph.AddDummyNode(retNode);
            }
            currOutGraph.Connect(
                retNode, nodes, PointsToGraph.emptyEdge, false);
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
                    ProcessInvokeExpr(u, invokeExpr);
                    return;
                }
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

    private void ProcessInvokeExpr(Unit u, InvokeExpr invokeExpr) {
        Boolean isInAssgnStmt;
        if (u instanceof JAssignStmt) {
            isInAssgnStmt = true;
        } else {
            isInAssgnStmt = false;
        }
        PointsToGraph currInGraph = inGraph.get(u);
        PointsToGraph currOutGraph = currInGraph.GetDeepCopy();
        for (Iterator<Edge> it = AnalysisTransformer.cg.edgesOutOf(u);
             it.hasNext();) {
            Edge cgEdge = it.next();
            SootMethod callee = cgEdge.tgt().method();
            MethodTransformer calleeAnalysis =
                AnalysisTransformer.Analyses.get(callee.toString());
            // System.out.println(AnalysisTransformer.Analyses + " " + callee);
            PointsToGraph calleeFinalPtg = calleeAnalysis.GetFinalPtg();
            calleeFinalPtg.RemoveLocals(); // can have dummy, heap, global nodes

            // --------- Mapping algo -----------

            // 1. replace param dummy nodes(along with this) with concrete
            // nodes
            PointsToGraph ptg = new PointsToGraph();
            HashMap<String, HashSet<String>> dummyToConcrete =
                new HashMap<String, HashSet<String>>();
            List<Value> args = invokeExpr.getArgs();
            for (int i = 0; i < args.size(); i++) {
                Value arg = args.get(i);
                HashSet<String> argNodes =
                    currInGraph.GetAllPointsToNodes(arg, u, false);
                String dummyNode = PointsToGraph.GetParamDummyNode(i);
                dummyToConcrete.put(dummyNode, argNodes);
            }
            if (invokeExpr instanceof JInterfaceInvokeExpr
                || invokeExpr instanceof JSpecialInvokeExpr
                || invokeExpr instanceof JVirtualInvokeExpr) {
                AbstractInstanceInvokeExpr instanceInvokeExpr =
                    (AbstractInstanceInvokeExpr) invokeExpr;
                Value base = instanceInvokeExpr.getBase();
                String thisNode = PointsToGraph.GetThisDummyNode();
                HashSet<String> baseNodes =
                    currInGraph.GetAllPointsToNodes(base, u, false);
                dummyToConcrete.put(thisNode, baseNodes);
            }

            // 2. add edges in ptg using dummyToConcrete &
            // calleeFinalPtg
            // It handles all kind of edges (dummy-dummy, dummy-concrete,
            // concrete-concret)
            ptg.MapNodes(currInGraph, calleeFinalPtg, dummyToConcrete);

            // 3. add edges from returnNode in ptg
            String retNode = PointsToGraph.GetReturnNode();
            HashSet<String> retDNodes =
                calleeFinalPtg.GetAllPointsToNodes(retNode);
            HashSet<String> retCNodes = new HashSet<String>();
            for (String retDNode : retDNodes) {
                if (calleeFinalPtg.IsHeapNode(retDNode)) {
                    retCNodes.add(retDNode);
                } else if (dummyToConcrete.containsKey(retDNode)) {
                    retCNodes.addAll(dummyToConcrete.get(retDNode));
                }
            }
            ptg.AddDummyNode(retNode);
            for (String node : retCNodes) {
                ptg.AddHeapNode(node);
            }
            ptg.Connect(retNode, retCNodes, PointsToGraph.emptyEdge, false);

            // 4. If isInAssignStmt, add edges from leftOp to retNode
            // pointees
            if (isInAssgnStmt) {
                JAssignStmt assignStmt = (JAssignStmt) u;
                Value leftOp = assignStmt.getLeftOp();
                HashSet<String> lNodes =
                    currInGraph.GetAllPointsToNodes(leftOp, u, true);
                for (String node : lNodes) {
                    if (currInGraph.IsHeapNode(node)) {
                        ptg.AddHeapNode(node);
                    } else if (currInGraph.IsLocalNode(node)) {
                        ptg.AddLocalNode(node);
                    } else if (currInGraph.IsDummy(node)) {
                        ptg.AddDummyNode(node);
                    } else if (currInGraph.IsGlobalNode(node)) {
                        ptg.AddGlobalNode(node);
                    }
                }
                ptg.Connect(lNodes, retCNodes, PointsToGraph.emptyEdge, false);
            }

            // 5. final steps
            ptg.RemoveNode(retNode);
            currOutGraph.Union(ptg);
        }
        inGraph.put(u, currInGraph);
        outGraph.put(u, currOutGraph);
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
        JVirtualInvokeExpr jVirtualInvokeExpr;
        if (u instanceof JAssignStmt) {
            Value rightOp = ((JAssignStmt) u).getRightOp();
            if (rightOp instanceof InvokeExpr) {
                InvokeExpr invokeExpr = (InvokeExpr) rightOp;
                if (invokeExpr instanceof JVirtualInvokeExpr) {
                    jVirtualInvokeExpr = (JVirtualInvokeExpr) invokeExpr;
                } else {
                    throw new RuntimeException("Not a virtual Invoke");
                }
            } else {
                throw new RuntimeException("Not a virtual Invoke");
            }
        } else if (u instanceof JInvokeStmt) {
            InvokeExpr invokeExpr = ((JInvokeStmt) u).getInvokeExpr();
            if (invokeExpr instanceof JVirtualInvokeExpr) {
                jVirtualInvokeExpr = (JVirtualInvokeExpr) invokeExpr;
            } else {
                throw new RuntimeException("Not a virtual Invoke");
            }
        } else {
            throw new RuntimeException("Not an invoke Expression");
        }
        Body targetBody =
            (Body) AnalysisTransformer.Analyses.get(targetMethod.toString())
                .body.clone();
        PatchingChain<Unit> targetUnits = targetBody.getUnits();
        PatchingChain<Unit> newUnits =
            new PatchingChain<Unit>(new HashChain<>());
        HashMap<String, Local> localsMapping = new HashMap<String, Local>();
        List<Local> parameterLocals = targetBody.getParameterLocals();
        List<Value> args = jVirtualInvokeExpr.getArgs();
        Local thisLocal = targetBody.getThisLocal();
        Local base = (Local) jVirtualInvokeExpr.getBase();
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
                if (op instanceof Local) {
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
                } else if (op
                    instanceof JInstanceFieldRef) { // TODO: handle "return
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

        // remove u from units and add all new units to the worklist & units
        units.insertAfter(newUnits, u);
        units.remove(u);
        workList.addAll(newUnits);
    }

    // renames the local using the mapping given and returns true if the
    // unit can be added to the newUnits set Note: It doesn't process return and
    // if statements' target unit (handled in inlineMethod itself)
    private Boolean ProcessUnitForRenaming(
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
            // ProcessUnitForRenaming(tgt, localsMapping);
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

    private void RenameInvokeExpr(
        InvokeExpr invokeExpr, HashMap<String, Local> localsMapping) {
        if (invokeExpr instanceof JVirtualInvokeExpr) {
            JVirtualInvokeExpr jVirtualInvokeExpr =
                (JVirtualInvokeExpr) invokeExpr;
            Value base = jVirtualInvokeExpr.getBase();
            if (base instanceof Local) {
                Local baseLocal = (Local) base;
                jVirtualInvokeExpr.setBase(
                    localsMapping.get(baseLocal.toString()));
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
    private Local getNewLocal(Type type) {
        return localGenerator.generateLocal(type);
    }

    public PointsToGraph GetFinalPtg() {
        return finalPtg.GetDeepCopy();
    }
}