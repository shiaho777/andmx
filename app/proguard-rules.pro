# AndMX proguard rules
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# AppStrings loads str_* entries via reflection on R.string
-keepclassmembers class com.andmx.R$string {
    public static int str_*;
}
