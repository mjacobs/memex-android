# R8 rules for the releaseInstrumented test APK.
#
# The instrumented test APK is minified alongside the app it tests (so it can
# call into the app's obfuscated classes), but its own classpath pulls in
# compile-time-only annotation dependencies (e.g. Guava's error-prone
# annotations) whose supporting javax.lang.model classes aren't present at
# runtime. They're never touched at runtime, only referenced by annotations,
# so silence R8's missing-class warnings for them instead of shipping the
# whole javac annotation-processing API.
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**
