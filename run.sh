#!/bin/bash

SOOTJAR=sootclasses-trunk-jar-with-dependencies.jar

# For robustness
set -o errexit
set -o nounset
# set -x

rm -rf sootOutput
rm -rf testOutput

progOutput="progOutput"
jimpleOutput="jimpleOutput"
mkdir -p $progOutput
mkdir -p $jimpleOutput

# executes 
Exec()
{
    local tcType=$1
    local optflag=$2
    # dir with required class files
    cdir=build/$tcType/$optflag
    # this file stores application's output
    local outfile=../../../$progOutput/${tcType}_${optflag}.txt

    cd $cdir
    #TODO: replace this with openj9 stuff
    if [ "tcType" == "heavy" ]; then
        java -cp . Test > $outfile
    else
        # Note that sanityChecks.java need 2 integer input
        echo 40 | java -cp . Test > $outfile
    fi
    echo 1 | java -cp . Test > $outfile
    cd ../../../

    #TODO: print perf stats (time and #virtual invokes)
    # echo -e "\e[1;35m\n--- Perf. stats: $optflag ---\n\e[0m"
}

# runs soot and dumps final class files in build directory
RunSoot()
{
    local tcType=$1
    local optflag=$2
    if [ "$optflag" == "withoutopt" ]; then
        # Run the analysis without optimization
        java -cp ".:$SOOTJAR" Main --disable-opt
    else
        # Run the analysis with optimization
        java -cp ".:$SOOTJAR" Main
    fi
    
    # Copy jimple files to the output directory
    jOut=$jimpleOutput/$tcType/$optflag
    mkdir -p $jOut
    cp sootOutput/* $jOut

    # Transform Jimple output to bytecode
    java -cp ".:$SOOTJAR" soot.Main -f class -cp sootOutput/ -pp -d build -process-dir sootOutput

    cdir=build/$tcType/$optflag/
    mkdir -p $cdir
    mv build/*.class $cdir

}

Run()
{
    local tcType=$1
    local filename=$1.java

    echo -e "\e[1;96m\n\n============= Testcase: $filename ==================\n\n\e[0m"

    # Copy the Java file to the 'testcase' directory
    cat "allTCs/$filename" > "testcase/Test.java"

    # Compile the testcase
    javac -g "testcase/Test.java"

    RunSoot $tcType "withoutopt"
    RunSoot $tcType "withopt"

    Exec $tcType "withoutopt"
    Exec $tcType "withopt"


    # Compare application outputs
    if cmp -s "$progOutput/${tcType}_withopt.txt" "$progOutput/${tcType}_withoutopt.txt"; then
        echo -e "\e[1;32m\nAppln Outputs matched\n\e[0m"
    else
        echo -e "\e[1;32m\nAppln Outputs did not match\n\e[0m"
    fi

}

# Compile
javac -g -cp .:$SOOTJAR *.java

if [ $# -eq 0 ]; then
    Run "heavy"
    Run "sanityChecks"
else
    # Process the argument to decide which test to run
    case "$1" in
        "heavy")
            Run "heavy"
            ;;
        "sanityChecks")
            Run "sanityChecks"
            ;;
        *)
            echo "Error: Unknown test '$1'. Valid options are 'heavy' or 'sanityChecks'."
            exit 1
            ;;
    esac
fi



# clean up
rm *.class
rm testcase/*.class

