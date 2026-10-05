# Keep default ProGuard rules for a debug-friendly prototype.
# No obfuscation is enabled in debug builds.
# WorkManager loads its default InputMerger by name and reflectively invokes this constructor.
-keep class androidx.work.OverwritingInputMerger { public <init>(); }
