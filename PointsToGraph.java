import fj.Hash;
import java.util.*;
import soot.*;
import soot.jimple.AnyNewExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.ParameterRef;
import soot.jimple.StaticFieldRef;
import soot.jimple.ThisRef;
import soot.jimple.internal.AbstractDefinitionStmt;
import soot.jimple.internal.AbstractInstanceInvokeExpr;
import soot.jimple.internal.JArrayRef;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JBreakpointStmt;
import soot.jimple.internal.JCastExpr;
import soot.jimple.internal.JDynamicInvokeExpr;
import soot.jimple.internal.JEnterMonitorStmt;
import soot.jimple.internal.JExitMonitorStmt;
import soot.jimple.internal.JGotoStmt;
import soot.jimple.internal.JIdentityStmt;
import soot.jimple.internal.JIfStmt;
import soot.jimple.internal.JInstanceFieldRef;
import soot.jimple.internal.JInterfaceInvokeExpr;
import soot.jimple.internal.JInvokeStmt;
import soot.jimple.internal.JLookupSwitchStmt;
import soot.jimple.internal.JNewArrayExpr;
import soot.jimple.internal.JNewExpr;
import soot.jimple.internal.JNewMultiArrayExpr;
import soot.jimple.internal.JNopStmt;
import soot.jimple.internal.JRetStmt;
import soot.jimple.internal.JReturnStmt;
import soot.jimple.internal.JReturnVoidStmt;
import soot.jimple.internal.JSpecialInvokeExpr;
import soot.jimple.internal.JStaticInvokeExpr;
import soot.jimple.internal.JTableSwitchStmt;
import soot.jimple.internal.JThrowStmt;
import soot.jimple.internal.JVirtualInvokeExpr;
import soot.jimple.internal.JimpleLocal;
import soot.toolkits.graph.*;
import soot.toolkits.scalar.BackwardFlowAnalysis;
import soot.toolkits.scalar.FlowSet;

class PointsToGraph {
    static String emptyEdge = "-";
    HashMap<String, HashMap<String, HashSet<String>>> graph;
    HashSet<String> escapingObjects;
    HashSet<String> dummyNodes;
    HashSet<String> localNodes;
    HashSet<String> heapNodes;
    HashSet<String> globalNodes;
    HashSet<String> allNodes;
    HashMap<String, String> objectTypes;
    // allNodes = dummyNodes U localNodes U heapNodes U globalNodes
    public PointsToGraph() {
        graph = new HashMap<String, HashMap<String, HashSet<String>>>();
        escapingObjects = new HashSet<String>();
        dummyNodes = new HashSet<String>();
        localNodes = new HashSet<String>();
        heapNodes = new HashSet<String>();
        globalNodes = new HashSet<String>();
        allNodes = new HashSet<String>();
        objectTypes = new HashMap<String, String>();
    }

    public void Union(PointsToGraph ptg) {
        for (String key : ptg.graph.keySet()) {
            if (!graph.containsKey(key)) {
                graph.put(key, new HashMap<String, HashSet<String>>());
            }
            for (String key2 : ptg.graph.get(key).keySet()) {
                if (!graph.get(key).containsKey(key2)) {
                    graph.get(key).put(key2, new HashSet<String>());
                }
                graph.get(key).get(key2).addAll(ptg.graph.get(key).get(key2));
            }
        }
        escapingObjects.addAll(ptg.escapingObjects);
        dummyNodes.addAll(ptg.dummyNodes);
        localNodes.addAll(ptg.localNodes);
        heapNodes.addAll(ptg.heapNodes);
        globalNodes.addAll(ptg.globalNodes);
        allNodes.addAll(ptg.allNodes);
        objectTypes.putAll(ptg.objectTypes);
    }

    public PointsToGraph GetDeepCopy() {
        PointsToGraph ptg = new PointsToGraph();
        for (String key : graph.keySet()) {
            ptg.graph.put(key, new HashMap<String, HashSet<String>>());
            for (String key2 : graph.get(key).keySet()) {
                ptg.graph.get(key).put(key2, new HashSet<String>());
                ptg.graph.get(key).get(key2).addAll(graph.get(key).get(key2));
            }
        }
        ptg.escapingObjects.addAll(escapingObjects);
        ptg.dummyNodes.addAll(dummyNodes);
        ptg.localNodes.addAll(localNodes);
        ptg.heapNodes.addAll(heapNodes);
        ptg.globalNodes.addAll(globalNodes);
        ptg.allNodes.addAll(allNodes);
        ptg.objectTypes.putAll(objectTypes);
        return ptg;
    }

    public Boolean EquivTo(PointsToGraph ptg) {
        // TODO: check
        for (String key : graph.keySet()) {
            if (ptg.graph.containsKey(key)) {
                for (String key2 : graph.get(key).keySet()) {
                    if (ptg.graph.get(key).containsKey(key2)) {
                        if (!graph.get(key).get(key2).equals(
                                ptg.graph.get(key).get(key2))) {
                            return false;
                        }
                    } else {
                        return false;
                    }
                }
            } else {
                return false;
            }
        }
        if (!escapingObjects.equals(ptg.escapingObjects))
            return false;
        if (!dummyNodes.equals(ptg.dummyNodes))
            return false;
        if (!localNodes.equals(ptg.localNodes))
            return false;
        if (!heapNodes.equals(ptg.heapNodes))
            return false;
        if (!globalNodes.equals(ptg.globalNodes))
            return false;
        if (!allNodes.equals(ptg.allNodes))
            return false;
        return true;
    }
    // Use strictly with stack vars
    public HashSet<String> GetAllPointsToNodes(String node) {
        HashSet<String> nodes = new HashSet<String>();
        if (graph.containsKey(node)) {
            if (graph.get(node).containsKey(emptyEdge)) {
                nodes.addAll(graph.get(node).get(emptyEdge));
            }
        }
        return nodes;
    }

    public HashSet<String> GetAllPointsToNodes(
        Value value, Unit u, Boolean isLeftOp) {
        HashSet<String> nodes = new HashSet<String>();
        if (value instanceof JArrayRef) {
            JArrayRef jArrayRef = (JArrayRef) value;
            Value base = jArrayRef.getBase();
            String baseNode = base.toString();
            if (isLeftOp) {
                if (graph.containsKey(baseNode)) {
                    if (graph.get(baseNode).containsKey(emptyEdge)) {
                        nodes.addAll(graph.get(baseNode).get(emptyEdge));
                    }
                }
            } else {
                if (graph.containsKey(baseNode)) {
                    if (graph.get(baseNode).containsKey(emptyEdge)) {
                        for (String lev1Node :
                            graph.get(baseNode).get(emptyEdge)) {
                            if (graph.get(lev1Node).containsKey("[]")) {
                                for (String refNode :
                                    graph.get(lev1Node).get("[]")) {
                                    nodes.add(refNode);
                                }
                            }
                        }
                    }
                }
            }
        } else if (value instanceof JCastExpr) {
            JCastExpr jCastExpr = (JCastExpr) value;
            return GetAllPointsToNodes(jCastExpr.getOp(), u, false);
        } else if (value instanceof JimpleLocal) {
            String localNode = value.toString();
            if (!graph.containsKey(localNode)) {
                AddLocalNode(localNode);
            }
            if (isLeftOp)
                nodes.add(localNode);
            else {
                if (graph.get(localNode).containsKey(emptyEdge)) {
                    nodes.addAll(graph.get(localNode).get(emptyEdge));
                }
            }
        } else if (value instanceof JInstanceFieldRef) {
            JInstanceFieldRef jInstanceFieldRef = (JInstanceFieldRef) value;
            String baseStr = jInstanceFieldRef.getBase().toString();
            String fieldStr = jInstanceFieldRef.getField().toString();
            if (graph.containsKey(baseStr)) {
                if (graph.get(baseStr).containsKey(emptyEdge)) {
                    if (isLeftOp)
                        nodes.addAll(graph.get(baseStr).get(emptyEdge));
                    else {
                        for (String lev1Node :
                            graph.get(baseStr).get(emptyEdge)) {
                            if (IsDummy(lev1Node)) {
                                // Using line number abstraction
                                String dummyNode = lev1Node;
                                String ln_dummyNode = "@dummy_ln#"
                                    + u.getJavaSourceStartLineNumber();
                                if (!allNodes.contains(ln_dummyNode)) {
                                    AddDummyNode(ln_dummyNode);
                                }
                                ConnectTwoNodes(
                                    dummyNode, ln_dummyNode, fieldStr, false);
                                nodes.add(ln_dummyNode);
                            }

                            if (!graph.get(lev1Node).containsKey(fieldStr))
                                continue;
                            for (String node :
                                graph.get(lev1Node).get(fieldStr)) {
                                nodes.add(node);
                            }
                        }
                    }
                }
            }
        } else if (value instanceof JNewExpr || value instanceof JNewArrayExpr
            || value
                instanceof JNewMultiArrayExpr) { // array new()s are also to be
            // handled the same way
            String objectNode =
                Integer.toString(u.getJavaSourceStartLineNumber());
            objectTypes.put(objectNode, value.getType().toString());
            AddHeapNode(objectNode);
            nodes.add(objectNode);
        } else if (value instanceof NullType) {
        } else if (value instanceof ParameterRef) {
            // TODO: check, maybe create another dummy node? can it be on
            // rhs?
            if (!(value.getType() instanceof PrimType)) {
                ParameterRef parameterRef = (ParameterRef) value;
                String dummyNode = GetParamDummyNode(parameterRef.getIndex());
                AddDummyNode(dummyNode);
                nodes.add(dummyNode);
            }
        } else if (value instanceof StaticFieldRef) {
            if (!(value.getType() instanceof PrimType)) {
                StaticFieldRef staticFieldRef = (StaticFieldRef) value;
                String staticField = staticFieldRef.getField().toString();
                String globalNode = "global_ln#"
                    + u.getJavaSourceStartLineNumber() + "_" + staticField;
                if (!globalNodes.contains(globalNode)) {
                    AddGlobalNode(globalNode);
                }
                if (isLeftOp) { // can only be in the rhs
                    // nodes.add(globalNode);
                } else {
                    nodes.add(globalNode);
                }
            }

        } else if (value instanceof ThisRef) {
            String thisNode = GetThisDummyNode();
            String thisRef = GetThisRefNode();
            if (!allNodes.contains(thisNode)) {
                AddDummyNode(thisNode);
            }
            if (!localNodes.contains(thisRef)) {
                AddLocalNode(thisRef);
            }
            ConnectTwoNodes(thisRef, thisNode, "-", false);
            if (isLeftOp)
                nodes.add(thisRef);
            else
                nodes.add(thisNode);
        } else if (value instanceof JInterfaceInvokeExpr
            || value instanceof JSpecialInvokeExpr
            || value instanceof JStaticInvokeExpr
            || value instanceof JVirtualInvokeExpr) {
            // Note that affected arguments are handled in ProcessUnit itself
            InvokeExpr invokeExpr = (InvokeExpr) value;
            SootMethod method = invokeExpr.getMethod();
            if (method.isJavaLibraryMethod()) {
                String dummyNode =
                    "@dummy_libcall_#" + u.getJavaSourceStartLineNumber();
                // System.out.println(dummyNode);
                if (!dummyNodes.contains(dummyNode))
                    AddDummyNode(dummyNode);
                nodes.add(dummyNode);
            } else {
                // handled in ProcessInvokeExpr()
            }

        } else if (value instanceof JDynamicInvokeExpr) {
        }
        return nodes;
    }

    public void Connect(String lNode, HashSet<String> rNodes, String edge,
        Boolean isStrongUpdate) {
        HashSet<String> lNodes = new HashSet<>();
        lNodes.add(lNode);
        Connect(lNodes, rNodes, edge, isStrongUpdate);
    }
    public void Connect(HashSet<String> lNodes, HashSet<String> rNodes,
        String edge, Boolean isStrongUpdate) {
        for (String lNode : lNodes) {
            if (!graph.containsKey(lNode))
                throw new RuntimeException(
                    "lNode not in graph: " + graph + " " + lNode);
        }
        for (String rNode : rNodes) {
            if (!graph.containsKey(rNode))
                throw new RuntimeException(
                    "rNode not in graph: " + graph + " " + rNode);
        }
        // connect
        if (isStrongUpdate) { // JInstanceFieldRef or JArrayRef
            for (String lNode : lNodes) {
                if (!graph.get(lNode).containsKey(edge))
                    graph.get(lNode).put(edge, new HashSet<String>());
                graph.get(lNode).get(edge).clear();
                if (!rNodes.isEmpty())
                    graph.get(lNode).get(edge).addAll(rNodes);
            }
        } else {
            for (String lNode : lNodes) {
                if (!graph.get(lNode).containsKey(edge))
                    graph.get(lNode).put(edge, new HashSet<String>());
                if (!rNodes.isEmpty())
                    graph.get(lNode).get(edge).addAll(rNodes);
            }
        }
    }

    public void ConnectTwoNodes(
        String lNode, String rNode, String edge, Boolean isStrongUpdate) {
        HashSet<String> rNodes = new HashSet<String>();
        rNodes.add(rNode);
        HashSet<String> lNodes = new HashSet<String>();
        lNodes.add(lNode);
        Connect(lNodes, rNodes, edge, isStrongUpdate);
    }

    public void RemoveLocals() {
        for (String node : localNodes) {
            graph.remove(node);
            allNodes.remove(node);
        }
        localNodes.clear();
    }
    public Boolean IsDummy(String node) {
        return dummyNodes.contains(node);
    }

    public Boolean IsLocalNode(String node) {
        return localNodes.contains(node);
    }

    public Boolean IsHeapNode(String node) {
        return heapNodes.contains(node);
    }

    public Boolean IsGlobalNode(String node) {
        return globalNodes.contains(node);
    }

    public void MarkAsEscaping(HashSet<String> nodes) {
        escapingObjects.addAll(nodes);
    }

    public void RemoveNode(String node) {
        graph.remove(node);
    }
    public void AddLocalNode(String node) {
        if (graph.containsKey(node)) {
            return;
        }
        graph.put(node, new HashMap<String, HashSet<String>>());
        localNodes.add(node);
        allNodes.add(node);
    }

    public void AddHeapNode(String node) {
        if (graph.containsKey(node)) {
            return;
        }
        graph.put(node, new HashMap<String, HashSet<String>>());
        heapNodes.add(node);
        allNodes.add(node);
    }

    public void AddGlobalNode(String node) { // static & this nodes
        if (graph.containsKey(node)) {
            return;
        }
        graph.put(node, new HashMap<String, HashSet<String>>());
        globalNodes.add(node);
        escapingObjects.add(node);
        allNodes.add(node);

        // String dummyNode = "@dummy_" + node;
        // AddDummyNode(dummyNode);

        // ConnectTwoNodes(node, dummyNode, emptyEdge, true);
    }

    public void AddDummyNode(String node) {
        if (graph.containsKey(node)) {
            return;
        }
        graph.put(node, new HashMap<String, HashSet<String>>());
        dummyNodes.add(node);
        allNodes.add(node);
        escapingObjects.add(node);
    }

    public List<Integer> GetEscapingObjects() {
        HashSet<String> allEscapingObjects = new HashSet<String>();
        for (String node : escapingObjects) {
            AddReachableNodes(node, allEscapingObjects);
        }
        List<Integer> lineNumbers = new ArrayList<Integer>();
        for (String node : allEscapingObjects) {
            if (IsHeapNode(node)) {
                lineNumbers.add(Integer.parseInt(node));
            }
        }
        Collections.sort(lineNumbers);
        return lineNumbers;
    }

    public void AddReachableNodes(String node, HashSet<String> reachableNodes) {
        reachableNodes.add(node);
        if (graph.containsKey(node)) {
            for (String edge : graph.get(node).keySet()) {
                for (String nextNode : graph.get(node).get(edge)) {
                    if (!reachableNodes.contains(nextNode)) {
                        AddReachableNodes(nextNode, reachableNodes);
                    }
                }
            }
        }
    }

    public void AddHeapNodes(HashSet<String> nodes) {
        for (String node : nodes) {
            AddHeapNode(node);
        }
    }

    public void MapNodes(PointsToGraph currInGraph, PointsToGraph calleePtg,
        HashMap<String, HashSet<String>> dummyToConcrete) {
        // Fill dummy nodes created by line# abstraction
        HashSet<String> workList = new HashSet<String>();
        for (String node : dummyToConcrete.keySet()) {
            workList.add(node);
        }
        while (!workList.isEmpty()) {
            String node = workList.iterator().next();
            workList.remove(node);
            if (!calleePtg.graph.containsKey(node)) {
                continue;
            }
            for (String edge : calleePtg.graph.get(node).keySet()) {
                for (String nextNode : calleePtg.graph.get(node).get(edge)) {
                    if (!calleePtg.IsDummy(nextNode))
                        continue;
                    if (!dummyToConcrete.containsKey(nextNode)) {
                        dummyToConcrete.put(nextNode, new HashSet<String>());
                    }
                    HashSet<String> prevSet =
                        new HashSet<String>(dummyToConcrete.get(nextNode));
                    HashSet<String> cNodes =
                        new HashSet<String>(dummyToConcrete.get(node));
                    for (String concreteNode : cNodes) {
                        if (currInGraph.ContainsNode(
                                concreteNode)) { // should be always true
                            if (currInGraph.graph.get(concreteNode)
                                    .containsKey(edge)) {
                                for (String nextConcreteNode :
                                    currInGraph.graph.get(concreteNode)
                                        .get(edge)) {
                                    dummyToConcrete.get(nextNode).add(
                                        nextConcreteNode);
                                }
                            }
                        }
                    }
                    if (!dummyToConcrete.get(nextNode).equals(prevSet)) {
                        workList.add(nextNode);
                    }
                }
            }
        }

        // add Edges
        for (String node : calleePtg.graph.keySet()) {
            HashSet<String> lNodes = new HashSet<String>();
            if (calleePtg.IsDummy(node)) {
                if (dummyToConcrete.containsKey(node)) {
                    HashSet<String> nodes = dummyToConcrete.get(node);
                    lNodes.addAll(nodes);
                    AddHeapNodes(nodes);
                }
            } else {
                lNodes.add(node);
                AddHeapNode(node);
            }
            for (String edge : calleePtg.graph.get(node).keySet()) {
                for (String nextNode : calleePtg.graph.get(node).get(edge)) {
                    HashSet<String> rNodes = new HashSet<String>();
                    if (calleePtg.IsDummy(nextNode)) {
                        if (dummyToConcrete.containsKey(nextNode)) {
                            HashSet<String> nodes =
                                dummyToConcrete.get(nextNode);
                            rNodes.addAll(nodes);
                            AddHeapNodes(nodes);
                        }
                    } else {
                        rNodes.add(nextNode);
                        AddHeapNode(nextNode);
                    }
                    Connect(lNodes, rNodes, edge, false);
                }
            }
        }
    }

    public HashSet<String> GetLocalNodes() {
        HashSet<String> ret = new HashSet<String>(localNodes);
        return ret;
    }

    public HashSet<String> GetDummyNodes() {
        HashSet<String> ret = new HashSet<String>(dummyNodes);
        return ret;
    }

    public static String GetParamDummyNode(int index) {
        return "@dummy_#" + String.valueOf(index);
    }

    public static String GetThisDummyNode() {
        return "@this";
    }

    public static String GetThisRefNode() {
        return "@thisRef";
    }

    public static String GetReturnNode() {
        return "@return";
    }

    public Boolean ContainsNode(String node) {
        return allNodes.contains(node);
    }
}