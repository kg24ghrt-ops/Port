#include <jni.h>
#include <string>
#include "tunnel_manager.h"
#include "port_selector.h"
#include <android/log.h>

#define LOG_TAG "PortMawer::JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_portmawer_PortSelectorWrapper_nativeInit(
    JNIEnv* env, jobject thiz, jstring host
) {
    const char* host_str = env->GetStringUTFChars(host, nullptr);
    TunnelManager::getInstance().initialize(std::string(host_str));
    env->ReleaseStringUTFChars(host, host_str);
    LOGI("Native initialized");
}

JNIEXPORT void JNICALL
Java_com_example_portmawer_PortSelectorWrapper_nativeShutdown(
    JNIEnv* env, jobject thiz
) {
    TunnelManager::getInstance().shutdown();
}

JNIEXPORT jboolean JNICALL
Java_com_example_portmawer_PortSelectorWrapper_nativeConnect(
    JNIEnv* env, jobject thiz
) {
    return TunnelManager::getInstance().connect() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_portmawer_PortSelectorWrapper_nativeDisconnect(
    JNIEnv* env, jobject thiz
) {
    TunnelManager::getInstance().disconnect();
}

JNIEXPORT void JNICALL
Java_com_example_portmawer_PortSelectorWrapper_nativeReconnect(
    JNIEnv* env, jobject thiz
) {
    TunnelManager::getInstance().reconnect();
}

JNIEXPORT jint JNICALL
Java_com_example_portmawer_PortSelectorWrapper_nativeGetActivePort(
    JNIEnv* env, jobject thiz
) {
    return TunnelManager::getInstance().getActivePort();
}

JNIEXPORT jint JNICALL
Java_com_example_portmawer_PortSelectorWrapper_nativeGetState(
    JNIEnv* env, jobject thiz
) {
    return static_cast<jint>(TunnelManager::getInstance().getState());
}

JNIEXPORT void JNICALL
Java_com_example_portmawer_PortSelectorWrapper_nativeTestPort(
    JNIEnv* env, jobject thiz, jint port
) {
    TunnelManager::getInstance().switchToPort(port);
}

JNIEXPORT jstring JNICALL
Java_com_example_portmawer_PortSelectorWrapper_nativeTestPortSync(
    JNIEnv* env, jobject thiz, jstring host, jint port
) {
    const char* host_str = env->GetStringUTFChars(host, nullptr);
    TestResult result = ConnectionTester::testPortWithDomain(
        std::string(host_str), port, 4000
    );
    env->ReleaseStringUTFChars(host, host_str);

    std::string response;
    if (result.success) {
        response = "OK:" + std::to_string(result.latency_ms);
    } else {
        response = "FAIL:" + result.response;
    }

    return env->NewStringUTF(response.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_portmawer_PortSelectorWrapper_nativeStartScan(
    JNIEnv* env, jobject thiz, jobject callback
) {
    // Store global reference to callback
    JavaVM* jvm;
    env->GetJavaVM(&jvm);
    jobject global_callback = env->NewGlobalRef(callback);

    TunnelManager::getInstance().testPorts(
        [jvm, global_callback](int port, bool success) {
            JNIEnv* cb_env;
            jvm->AttachCurrentThread(&cb_env, nullptr);

            jclass cls = cb_env->GetObjectClass(global_callback);
            jmethodID method = cb_env->GetMethodID(
                cls, "onPortTested", "(IZ)V"
            );
            if (method) {
                cb_env->CallVoidMethod(global_callback, method, port, success ? JNI_TRUE : JNI_FALSE);
            }

            jvm->DetachCurrentThread();
        }
    );
}

JNIEXPORT void JNICALL
Java_com_example_portmawer_PortSelectorWrapper_nativeStopScan(
    JNIEnv* env, jobject thiz
) {
    // Scan stops naturally or via selector
}

JNIEXPORT jstring JNICALL
Java_com_example_portmawer_PortSelectorWrapper_nativeGetStats(
    JNIEnv* env, jobject thiz
) {
    TunnelStats stats = TunnelManager::getInstance().getStats();

    std::string json = "{";
    json += "\"activePort\":" + std::to_string(stats.active_port) + ",";
    json += "\"bytesSent\":" + std::to_string(stats.total_bytes_sent) + ",";
    json += "\"bytesReceived\":" + std::to_string(stats.total_bytes_received) + ",";
    json += "\"uptimeSeconds\":" + std::to_string(stats.connection_uptime_seconds) + ",";
    json += "\"reconnections\":" + std::to_string(stats.reconnection_count) + ",";
    json += "\"targetHost\":\"" + stats.target_host + "\"";
    json += "}";

    return env->NewStringUTF(json.c_str());
}

} // extern "C"