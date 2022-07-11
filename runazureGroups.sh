#!/bin/bash

export LC_ALL=en_US.UTF-8
export JAVA_HOME=/idm/scripts/supportfiles/jdk1.8.0_40
export GROOVY_HOME=/idm/scripts/supportfiles/groovy-3.0.8
export SUPPORT_LIBS=/idm/scripts/supportfiles/lib
export CLASSPATH=$SUPPORT_LIBS/ojdbc8.jar:$SUPPORT_LIBS/unboundid-ldapsdk-6.0.3.jar
export PATH=$JAVA_HOME/bin:$GROOVY_HOME/bin:$PATH

cd /idm/scripts/jenkins/azureADDivGroups

./azureDivGroups.groovy $1
