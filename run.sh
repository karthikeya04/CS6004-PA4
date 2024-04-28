#!/bin/bash

SOOTJAR=sootclasses-trunk-jar-with-dependencies.jar

# For robustness
set -o errexit
set -o nounset
set -x


# Build testcase
cd testcase
javac -g *.java
cd ..

rm -rf sootOutput

# Run the analysis
javac -g -cp .:$SOOTJAR *.java
java -cp .:$SOOTJAR Main

# transform jimple output to bytecode
java -cp .:$SOOTJAR soot.Main -f class -cp sootOutput/ -pp -d build -process-dir sootOutput

# clean up
rm *.class
rm testcase/*.class
