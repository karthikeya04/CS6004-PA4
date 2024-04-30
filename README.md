# README.md

## **CS 60004 Assignment PA4: Transform Me If You Can**

Commands to Run 

```bash
./run.sh
```

This executes the testcases and creates class files with and without optimization in ./build folder 

Use these class files to test optimizations by modifying the `ByteCodeInterpreter.hpp`, `ByteCodeInterpreter.inc` in openj9 JVM

Directory Structure 

```bash
.
 |-allTCs
 | |-heavy.java
 | |-sanityChecks.java
 |-AnalysisTransformer.java
 |-ideas.txt
 |-jimpleOutput
 |-Main.java
 |-MethodTransformer.java
 |-PointsToGraph.java
 |-problem_statement.pdf
 |-progOutput
 |-README.md
 |-run.sh
 |-sootclasses-trunk-jar-with-dependencies.jar
 |-testcase
 | |-Test.java
 |-todos.txt
```