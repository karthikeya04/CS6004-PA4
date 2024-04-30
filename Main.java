import java.util.List;
import javax.swing.text.html.Option;
import soot.*;
import soot.jimple.internal.*;
import soot.options.Options;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.graph.UnitGraph;

public class Main {
    public static void main(String[] args) {
        Boolean enableOptimization = true;
        if (args.length == 1) {
            if (args[0].equals("--disable-opt")) {
                enableOptimization = false;
            } else {
                System.out.println(
                    "Invalid argument. Usage: java Main [--disable-opt]");
                return;
            }
        } else if (args.length > 0) {
            System.out.println("Usage: java Main [--disable-opt]");
            return;
        }
        String classPath = "."; // change to appropriate path to the test class
        String dir = "./testcase";

        // Set up arguments for Soot
        String[] sootArgs = {
            "-cp", classPath, "-pp", // sets the class path for Soot
            "-w", // whole program analysis
            "-f", "J", // jimple file
            "-print-tags",
            "-keep-line-number", // preserves line numbers in input Java files
            "-main-class", "Test", // specify the main class
            "-process-dir", dir, // directory of classes to analyze
        };

        if (enableOptimization) {
            // Create transformer for analysis
            AnalysisTransformer analysisTransformer = new AnalysisTransformer();

            // Add transformer to appropriate pack in PackManager; PackManager
            // will run all packs when soot.Main.main is called
            PackManager.v().getPack("wjtp").add(
                new Transform("wjtp.gc", analysisTransformer));
        }
        // Call Soot's main method with arguments
        soot.Main.main(sootArgs);
    }
}
