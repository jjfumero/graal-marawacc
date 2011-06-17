#!/bin/bash
if [ -z "${JDK7}" ]; then
  echo "JDK7 is not defined."
  exit 1;
fi
if [ -z "${MAXINE}" ]; then
  echo "MAXINE is not defined. It must point to a maxine repository directory."
  exit 1;
fi
if [ -z "${GRAAL}" ]; then
  echo "GRAAL is not defined. It must point to a maxine repository directory."
  exit 1;
fi
if [ -z "${DACAPO}" ]; then
  echo "DACAPO is not defined. It must point to a Dacapo benchmark directory."
  exit 1;
fi
${JDK7}/bin/java -client -d64 -graal -G:+PrintCompilation -G:+DetailedAsserts -G:PrintIdealGraphLevel=4 "-G:PrintFilter=~.*(fill|findBundle).*"  -XX:CompileCommand=compileonly,java.io.BufferedReader::fill -XX:-GraalBailoutIsFatal -XX:+PrintCompilation -Xms1g -Xmx2g -esa -classpath ${DACAPO}/dacapo-9.12-bach.jar Harness --preserve $*
