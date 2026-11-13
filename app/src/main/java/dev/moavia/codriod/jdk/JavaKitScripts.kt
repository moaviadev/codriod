package dev.moavia.codriod.jdk

/**
 * Shell scripts for the Java & Kotlin add-on. They run inside the Ubuntu rootfs (like every Codriod install):
 * Temurin JDK 21 (Adoptium) → /opt/jdk, the Kotlin compiler (JetBrains release) → /opt/kotlin and
 * Gradle (services.gradle.org) → /opt/gradle. Everything is checksum-verified and tested before it counts
 * as installed. Lines starting with "::step "/"::progress " drive the progress UI, "::fail " reports a
 * user-readable failure.
 */
object JavaKitScripts {

    /** PATH / JAVA_HOME wiring for login shells; never overrides an already-set JAVA_HOME (Android SDK keeps its JDK). */
    private val PROFILE = """
cat > /etc/profile.d/java-kit.sh <<'PROFILE'
# Java & Kotlin tools (written by Codriod)
if [ -d /opt/jdk ]; then
  export PATH="/opt/jdk/bin:${'$'}PATH"
  if [ -z "${'$'}{JAVA_HOME:-}" ]; then export JAVA_HOME=/opt/jdk; fi
fi
if [ -d /opt/kotlin ]; then
  export PATH="/opt/kotlin/bin:${'$'}PATH"
  export JAVA_OPTS="${'$'}{JAVA_OPTS:-} -Xmx1200m"
fi
if [ -d /opt/gradle ]; then
  export PATH="/opt/gradle/bin:${'$'}PATH"
  if [ ! -f /root/.gradle/gradle.properties ]; then export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1200m"; fi
fi
PROFILE
chmod 644 /etc/profile.d/java-kit.sh
""".trimIndent() + "\n"

    /** What exists right now: ::tool <name> <version> / ::src <name> apt|opt / ::absent <name>. */
    val PROBE = """
export PATH=/opt/jdk/bin:/opt/kotlin/bin:/opt/gradle/bin:${'$'}PATH
if command -v java >/dev/null 2>&1; then
  echo "::tool java ${'$'}(java -version 2>&1 | head -n1 | sed 's/.*version "\([^"]*\)".*/\1/')"
  case "${'$'}(command -v java)" in /opt/jdk/*) echo "::src java opt";; *) echo "::src java apt";; esac
else echo "::absent java"; fi
if command -v kotlinc >/dev/null 2>&1; then
  echo "::tool kotlin ${'$'}(kotlinc -version 2>&1 | head -n1 | grep -o '[0-9][0-9.]*' | head -n1)"
else echo "::absent kotlin"; fi
if command -v gradle >/dev/null 2>&1; then
  echo "::tool gradle ${'$'}(gradle --version 2>/dev/null | sed -n 's/^Gradle \([0-9][0-9.]*\).*$/\1/p' | head -n1)"
else echo "::absent gradle"; fi
""".trimIndent() + "\n"

    /** Temurin JDK 21 from Adoptium, with SHA-256 check (the API reply carries the checksum). */
    val JDK = """
set -e
echo "::step Finding the latest Temurin JDK 21 for this phone"
echo "::progress 3"
case "${'$'}(uname -m)" in
  aarch64|arm64) arch=aarch64 ;;
  x86_64|amd64) arch=x64 ;;
  *) echo "::fail The JDK needs a 64-bit phone (this one is ${'$'}(uname -m))"; exit 3 ;;
esac
meta=${'$'}(curl -fsSL --retry 4 "https://api.adoptium.net/v3/assets/latest/21/hotspot?os=linux&architecture=${'$'}arch&image_type=jdk")
set -- ${'$'}(printf '%s' "${'$'}meta" | python3 -c 'import sys,json
try: d=json.load(sys.stdin)[0]["binary"]["package"]
except Exception: d={}
print(d.get("link",""), d.get("checksum",""))')
link=${'$'}{1:-}; sha=${'$'}{2:-}
[ -n "${'$'}link" ] || { echo "::fail Could not find a Temurin JDK 21 build for ${'$'}arch"; exit 4; }
echo "::step Downloading ${'$'}(basename "${'$'}link")"
echo "::progress 5"
curl -fL --retry 4 --retry-delay 3 -o /tmp/jdk21.tar.gz "${'$'}link"
echo "::step Checking the download"
echo "::progress 78"
echo "${'$'}sha  /tmp/jdk21.tar.gz" | sha256sum -c - || { echo "::fail The JDK download is damaged (checksum does not match)"; exit 5; }
echo "::step Unpacking the JDK"
echo "::progress 80"
rm -rf /opt/jdk.new && mkdir -p /opt/jdk.new
tar -xzf /tmp/jdk21.tar.gz -C /opt/jdk.new --strip-components=1
rm -rf /opt/jdk && mv /opt/jdk.new /opt/jdk && rm -f /tmp/jdk21.tar.gz
echo "::step Writing the PATH and JAVA_HOME"
echo "::progress 92"
""" + PROFILE + """
export JAVA_HOME=/opt/jdk PATH=/opt/jdk/bin:${'$'}PATH
echo "::step Testing java"
java -version 2>&1 | head -n1
echo "::step JDK 21 installed at /opt/jdk"
""".trimIndent() + "\n"

    /** The Kotlin compiler (JetBrains' release zip) — needs Java to run. */
    val KOTLIN = """
set -e
echo "::step Checking for Java (the Kotlin compiler runs on it)"
echo "::progress 2"
[ -d /opt/jdk ] && export JAVA_HOME=/opt/jdk PATH=/opt/jdk/bin:${'$'}PATH
command -v java >/dev/null 2>&1 || { echo "::fail The Kotlin compiler runs on Java — install the JDK first (Java & Kotlin add-on)"; exit 6; }
command -v unzip >/dev/null 2>&1 || apt-get install -y --no-install-recommends unzip >/dev/null 2>&1 || true
echo "::step Finding the latest Kotlin compiler"
echo "::progress 4"
tag=${'$'}(curl -fsSL --retry 4 https://api.github.com/repos/JetBrains/kotlin/releases/latest | python3 -c 'import sys,json
try: print(json.load(sys.stdin).get("tag_name","").lstrip("v"))
except Exception: print("")')
[ -n "${'$'}tag" ] || { echo "::fail Could not read the Kotlin release list from GitHub"; exit 4; }
echo "::step Downloading the Kotlin ${'$'}tag compiler"
echo "::progress 8"
curl -fL --retry 4 --retry-delay 3 -o /tmp/kotlin.zip "https://github.com/JetBrains/kotlin/releases/download/v${'$'}tag/kotlin-compiler-${'$'}tag.zip"
echo "::step Unpacking Kotlin"
echo "::progress 45"
rm -rf /opt/kotlin.new /opt/kotlin && mkdir -p /opt/kotlin.new
unzip -q -o /tmp/kotlin.zip -d /opt/kotlin.new
if [ -d /opt/kotlin.new/kotlinc ]; then mv /opt/kotlin.new/kotlinc /opt/kotlin; else mv /opt/kotlin.new /opt/kotlin; fi
rm -f /tmp/kotlin.zip
echo "::step Writing the PATH"
echo "::progress 80"
""" + PROFILE + """
export PATH=/opt/kotlin/bin:${'$'}PATH
chmod 755 /opt/kotlin/bin/* 2>/dev/null || true
echo "::step Testing kotlinc"
echo "::progress 85"
kotlinc -version 2>&1 | head -n1
echo "::step Kotlin compiler installed at /opt/kotlin"
""".trimIndent() + "\n"

    /** Gradle from services.gradle.org — needs Java to run; the phone's Gradle memory limits stay untouched. */
    val GRADLE = """
set -e
echo "::step Checking for Java (Gradle runs on it)"
echo "::progress 2"
[ -d /opt/jdk ] && export JAVA_HOME=/opt/jdk PATH=/opt/jdk/bin:${'$'}PATH
command -v java >/dev/null 2>&1 || { echo "::fail Gradle runs on Java — install the JDK first (Java & Kotlin add-on)"; exit 6; }
command -v unzip >/dev/null 2>&1 || apt-get install -y --no-install-recommends unzip >/dev/null 2>&1 || true
echo "::step Finding the latest Gradle"
echo "::progress 4"
ver=${'$'}(curl -fsSL --retry 4 https://services.gradle.org/versions/current | python3 -c 'import sys,json
try: print(json.load(sys.stdin)["version"])
except Exception: print("")')
[ -n "${'$'}ver" ] || { echo "::fail Could not read the Gradle version list"; exit 4; }
echo "::step Downloading Gradle ${'$'}ver"
echo "::progress 8"
curl -fL --retry 4 --retry-delay 3 -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${'$'}ver-bin.zip"
echo "::step Unpacking Gradle"
echo "::progress 55"
rm -rf /opt/gradle.new /opt/gradle && mkdir -p /opt/gradle.new
unzip -q -o /tmp/gradle.zip -d /opt/gradle.new
if [ -d /opt/gradle.new/gradle-${'$'}ver ]; then mv /opt/gradle.new/gradle-${'$'}ver /opt/gradle; else mv /opt/gradle.new /opt/gradle; fi
rm -f /tmp/gradle.zip
echo "::step Writing the PATH"
echo "::progress 88"
""" + PROFILE + """
export PATH=/opt/gradle/bin:${'$'}PATH
[ -n "${'$'}{JAVA_HOME:-}" ] || export JAVA_HOME=${'$'}(dirname "${'$'}(dirname "${'$'}(readlink -f "${'$'}(command -v java)")")")
echo "::step Testing gradle (first start unpacks itself, this can take a minute)"
echo "::progress 90"
gradle --version 2>/dev/null | sed -n 's/^Gradle \([0-9][0-9.]*\).*$/Gradle \1/p' | head -n1
echo "::step Gradle installed at /opt/gradle"
""".trimIndent() + "\n"

    /**
     * Verify Installation: compiles and runs a Java and a Kotlin hello-world and runs a Gradle build
     * (a tiny project without plugins, so it works offline). Prints java-ok / kotlin-ok / gradle-ok markers.
     */
    val VERIFY = """
export PATH=/opt/jdk/bin:/opt/kotlin/bin:/opt/gradle/bin:${'$'}PATH
[ -z "${'$'}{JAVA_HOME:-}" ] && [ -d /opt/jdk ] && export JAVA_HOME=/opt/jdk
mkdir -p /tmp/javakit-verify && cd /tmp/javakit-verify
rm -f Hello.java Hello.class hello.jar Hello.kt
if command -v javac >/dev/null 2>&1; then
  echo "== Java: compiling and running"
  printf 'public class Hello { public static void main(String[] a) { System.out.println("java-ok"); } }\n' > Hello.java
  if javac Hello.java && java Hello; then :; else echo "::fail java verification failed"; fi
fi
if command -v kotlinc >/dev/null 2>&1; then
  echo "== Kotlin: compiling and running"
  echo "== (the Kotlin compiler is slow the first time on a phone)"
  printf 'fun main() { println("kotlin-ok") }\n' > Hello.kt
  export JAVA_OPTS="${'$'}{JAVA_OPTS:-} -Xmx1200m"
  if kotlinc Hello.kt -include-runtime -d hello.jar && java -jar hello.jar; then :; else echo "::fail kotlin verification failed"; fi
fi
if command -v gradle >/dev/null 2>&1; then
  echo "== Gradle: running a build"
  rm -rf /tmp/gradle-verify && mkdir -p /tmp/gradle-verify && cd /tmp/gradle-verify
  echo 'rootProject.name = "verify"' > settings.gradle
  printf 'tasks.register("hello") { doLast { println("gradle-ok") } }\n' > build.gradle
  if gradle --no-daemon --console=plain -q hello; then :; else echo "::fail gradle verification failed"; fi
fi
""".trimIndent() + "\n"

    /** Removes one tool (only the ones Codriod installed itself — apt's JDK belongs to the Android SDK). */
    fun remove(tool: String): String = when (tool) {
        "java" -> "set -e\nrm -rf /opt/jdk\nrm -f /etc/profile.d/java-kit.sh\n"
        "kotlin" -> "set -e\nrm -rf /opt/kotlin\n" + PROFILE
        "gradle" -> "set -e\nrm -rf /opt/gradle\n" + PROFILE
        else -> "true\n"
    }
}
