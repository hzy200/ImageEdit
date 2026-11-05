#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_imageedit_core_image_NativeBridge_version(JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF("imageedit-native-v0");
}
