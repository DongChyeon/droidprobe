# The setup class is named in the manifest and created reflectively.
-keep class * implements dev.droidprobe.ProbeSetup { <init>(); }
-keep class dev.droidprobe.ProbeInitializer { <init>(); }
