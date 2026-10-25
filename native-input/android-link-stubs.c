/* SPDX-License-Identifier: GPL-3.0-or-later
 * Link-only symbol declarations for the receiver's public Android C APIs.
 * These DSOs are never packaged or executed. At runtime Android resolves their
 * SONAMEs to its own libc/libdl. Function bodies deliberately trap if misused.
 * New undefined symbols must be added explicitly after checking API availability.
 */
#define API(name) __attribute__((visibility("default"))) void name(void) { __builtin_trap(); }
#ifdef REA_STUB_LIBDL
API(dlopen) API(dlsym) API(dlerror) API(dlclose)
#else
API(pthread_mutex_lock) API(pthread_mutex_unlock) API(pthread_create)
API(pthread_detach) API(pthread_cond_broadcast) API(pthread_cond_wait)
API(strlen) API(strcpy) API(strcmp) API(snprintf) API(memcpy) API(memset) API(memcmp)
API(fopen) API(setbuf) API(fwrite) API(fprintf) API(fclose)
API(socket) API(unlink) API(bind) API(listen) API(__errno)
API(close) API(accept4) API(send) API(recv) API(recvmsg)
API(malloc) API(calloc) API(realloc) API(free) API(abort)
API(__stack_chk_fail)
__attribute__((visibility("default"))) unsigned long __stack_chk_guard;
#endif
