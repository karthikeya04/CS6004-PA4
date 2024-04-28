import java.util.*;
import soot.*;
import soot.jimple.spark.SparkTransformer;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;

public class AnalysisTransformer extends SceneTransformer {
    static CallGraph cg;
    Set<SootMethod> processedMethods;
    static HashMap<String, MethodTransformer> Analyses =
        new HashMap<String, MethodTransformer>();

    @Override
    protected synchronized void internalTransform(
        String arg0, Map<String, String> arg1) {
        // Build vta callgraph with main method as source
        SootMethod mainMethod = Scene.v().getMainMethod();
        Transform sparkConfig = new Transform("cg.spark", null);
        PhaseOptions.v().setPhaseOption(sparkConfig, "enabled:true");
        PhaseOptions.v().setPhaseOption(sparkConfig, "vta:true");
        PhaseOptions.v().setPhaseOption(sparkConfig, "on-fly-cg:false");
        Map<String, String> phaseOptions =
            PhaseOptions.v().getPhaseOptions(sparkConfig);
        SparkTransformer.v().transform(
            sparkConfig.getPhaseName(), phaseOptions);
        cg = Scene.v().getCallGraph();

        processedMethods = new HashSet<SootMethod>();
        // Process all the methods reachable from main method
        ProcessMethodsReachableFrom(mainMethod);
    }

    private void ProcessMethodsReachableFrom(SootMethod method) {
        if (processedMethods.contains(method)) {
            return;
        }
        processedMethods.add(method);
        // Iterate over the edges originating from this method
        Iterator<Edge> edges = Scene.v().getCallGraph().edgesOutOf(method);
        while (edges.hasNext()) {
            Edge edge = edges.next();
            SootMethod targetMethod = edge.tgt();
            // Recursively process callee methods
            if (!targetMethod.isJavaLibraryMethod()) {
                ProcessMethodsReachableFrom(targetMethod);
            }
        }
        if (method.toString().contains("init")) {
            return;
        }
        System.out.println("Analyzing method: " + method.toString());
        MethodTransformer analysis = new MethodTransformer(method);
        analysis.Analyze();
        Analyses.put(method.toString(), analysis);
        System.out.println(method.getActiveBody());
    }
}