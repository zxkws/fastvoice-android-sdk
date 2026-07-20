# sherpa-onnx Java declarations are called from JNI and must keep their names.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
