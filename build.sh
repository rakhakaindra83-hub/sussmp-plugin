#!/bin/bash
# Build SusSMP: javac + jar (plugin.yml di root JAR)
set -e
cd "$(dirname "$0")"
TOOLS="$LOCALAPPDATA/tools"
JDK21=$(ls -d "$TOOLS"/jdk-21* | head -1)
LIBS="$TOOLS/mc-libs"
CP="$LIBS/paper-api-1.21.8.jar;$LIBS/adventure-api-4.17.0.jar;$LIBS/adventure-key-4.17.0.jar;$LIBS/examination-api-1.3.0.jar;$LIBS/bungeecord-chat-1.20-R0.1.jar"

rm -rf classes
mkdir -p classes
find src -name '*.java' > sources.txt
"$JDK21/bin/javac" --release 21 -encoding UTF-8 -cp "$CP" -d classes @sources.txt
cp src/plugin.yml src/config.yml classes/
"$JDK21/bin/jar" cf sussmp-1.0.0.jar -C classes .
ls -la sussmp-1.0.0.jar
echo BUILD_OK
