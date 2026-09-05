#!/bin/bash
# Build SusSMP: javac + jar (plugin.yml di root JAR)
set -e
cd "$(dirname "$0")"
TOOLS="$LOCALAPPDATA/tools"
JDK21=$(ls -d "$TOOLS"/jdk-21* | head -1)
LIBS="C:/Users/TUF GAMING/AppData/Local/tools/mc-libs"
CP="$LIBS/paper-api-1.21.8.jar;$LIBS/adventure-api-4.17.0.jar;$LIBS/adventure-key-4.17.0.jar;$LIBS/examination-api-1.3.0.jar;$LIBS/bungeecord-chat-1.20-R0.1.jar"

# Clean previous build artifacts
rm -rf classes
mkdir -p classes

# Compile Java source files
# Menggunakan find untuk mendapatkan daftar file .java dan menyimpannya dalam variabel
JAVA_FILES=$(find src/main/java/id/kuru/sussmp -name '*.java')

"$JDK21/bin/javac" --release 21 -encoding UTF-8 -cp "$CP" -d classes $JAVA_FILES

# Copy plugin.yml and config.yml to the root of the JAR
cp src/plugin.yml classes/
cp src/config.yml classes/

# Create the JAR file
"$JDK21/bin/jar" cf sussmp.jar -C classes .

ls -la sussmp.jar
echo BUILD_OK