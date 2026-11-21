// Kuheli native layer.
//  * startNode()    runs the embedded Node.js runtime (only when libnode was bundled at build time)
//  * nodeAvailable() tells the Java side whether that happened
//  * sha256()        hashes a file, used to fingerprint attachments
#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#ifdef HAVE_NODE
#include "node.h"
#endif

#define LOG_TAG "Kuheli"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// ---- minimal SHA-256 ------------------------------------------------------------------
struct Sha256 {
    uint32_t h[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                     0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    uint8_t buf[64];
    size_t len = 0;
    uint64_t total = 0;

    static uint32_t rotr(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

    void block(const uint8_t* p) {
        static const uint32_t k[64] = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};
        uint32_t w[64];
        for (int i = 0; i < 16; i++)
            w[i] = (uint32_t)p[i * 4] << 24 | (uint32_t)p[i * 4 + 1] << 16 | (uint32_t)p[i * 4 + 2] << 8 | p[i * 4 + 3];
        for (int i = 16; i < 64; i++) {
            uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
            uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; i++) {
            uint32_t S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            uint32_t ch = (e & f) ^ (~e & g);
            uint32_t t1 = hh + S1 + ch + k[i] + w[i];
            uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            uint32_t mj = (a & b) ^ (a & c) ^ (b & c);
            uint32_t t2 = S0 + mj;
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    }

    void update(const uint8_t* p, size_t n) {
        total += n;
        while (n > 0) {
            size_t take = 64 - len < n ? 64 - len : n;
            memcpy(buf + len, p, take);
            len += take; p += take; n -= take;
            if (len == 64) { block(buf); len = 0; }
        }
    }

    std::string hex() {
        uint64_t bits = total * 8;
        uint8_t pad = 0x80;
        update(&pad, 1);
        uint8_t zero = 0;
        while (len != 56) update(&zero, 1);
        uint8_t lenbytes[8];
        for (int i = 0; i < 8; i++) lenbytes[i] = (uint8_t)(bits >> (56 - 8 * i));
        update(lenbytes, 8);
        char out[65];
        for (int i = 0; i < 8; i++) snprintf(out + i * 8, 9, "%08x", h[i]);
        return std::string(out, 64);
    }
};

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_com_kuheli_app_core_Native_sha256(JNIEnv* env, jclass, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    FILE* f = path ? fopen(path, "rb") : nullptr;
    std::string result;
    if (f) {
        Sha256 s;
        uint8_t chunk[8192];
        size_t n;
        while ((n = fread(chunk, 1, sizeof(chunk), f)) > 0) s.update(chunk, n);
        fclose(f);
        result = s.hex();
    }
    if (path) env->ReleaseStringUTFChars(jpath, path);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL Java_com_kuheli_app_core_Native_sha256Bytes(JNIEnv* env, jclass, jbyteArray data) {
    jsize n = env->GetArrayLength(data);
    jbyte* p = env->GetByteArrayElements(data, nullptr);
    Sha256 s;
    s.update(reinterpret_cast<const uint8_t*>(p), (size_t)n);
    env->ReleaseByteArrayElements(data, p, JNI_ABORT);
    return env->NewStringUTF(s.hex().c_str());
}

JNIEXPORT jboolean JNICALL Java_com_kuheli_app_core_Native_nodeAvailable(JNIEnv*, jclass) {
#ifdef HAVE_NODE
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

// Runs Node on the calling thread until it exits. Node can only be started once per process,
// and it expects its argv strings in one contiguous block.
JNIEXPORT jint JNICALL Java_com_kuheli_app_core_Native_startNode(JNIEnv* env, jclass, jobjectArray arguments) {
#ifdef HAVE_NODE
    jsize argc = env->GetArrayLength(arguments);
    size_t total = 0;
    for (jsize i = 0; i < argc; i++) {
        auto s = (jstring)env->GetObjectArrayElement(arguments, i);
        const char* c = env->GetStringUTFChars(s, nullptr);
        total += strlen(c) + 1;
        env->ReleaseStringUTFChars(s, c);
    }
    char* block = (char*)calloc(total, sizeof(char));
    char** argv = (char**)calloc((size_t)argc, sizeof(char*));
    char* cur = block;
    for (jsize i = 0; i < argc; i++) {
        auto s = (jstring)env->GetObjectArrayElement(arguments, i);
        const char* c = env->GetStringUTFChars(s, nullptr);
        strcpy(cur, c);
        argv[i] = cur;
        cur += strlen(c) + 1;
        env->ReleaseStringUTFChars(s, c);
    }
    LOGI("starting embedded Node with %d args", (int)argc);
    return (jint)node::Start((int)argc, argv);
#else
    (void)env; (void)arguments;
    LOGI("this build has no embedded Node runtime");
    return -1;
#endif
}

}  // extern "C"
