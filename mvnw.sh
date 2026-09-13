#!/usr/bin/env bash
# Duo 构建工具链（JDK 21 + Maven 3.9，安装于用户目录，不进仓库）
# Git Bash 下 JAVA_HOME 必须是 POSIX 路径，mvn.cmd 才能正确解析。
export JAVA_HOME="/c/Users/cwt15/devtools/jdk-21.0.12.1+1"
export PATH="$JAVA_HOME/bin:/c/Users/cwt15/devtools/apache-maven-3.9.11/bin:$PATH"
exec mvn "$@"
