/* GNU-side prefix of bionic android_dlextinfo. Only the namespace field is set. */
#ifndef ANDROID_DLEXT_H
#define ANDROID_DLEXT_H
#include <stdint.h>
#include <sys/types.h>
struct android_namespace_t;
typedef struct {
    uint64_t flags;
    void *reserved_addr;
    size_t reserved_size;
    int relro_fd;
    int library_fd;
    int64_t library_fd_offset;
    struct android_namespace_t *library_namespace;
} android_dlextinfo;
enum { ANDROID_DLEXT_USE_NAMESPACE = 0x200 };
#endif
