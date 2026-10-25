/* Project-owned GNU WSI native-window provider. XCB supplies geometry/input only.
 * GPU images are allocated by gralloc, imported by the ICD, and sent as AHB
 * handles to the app's Bionic Surface renderer. No pixel map/lock/readback. */
#define _GNU_SOURCE
#include "native_window.h"
#include <system/window.h>
#include <hybris/gralloc/gralloc.h>
#include <hybris/common/dlfcn.h>
#include <xcb/xcb.h>
#include <stddef.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <time.h>
#include <dlfcn.h>
_Static_assert(sizeof(ANativeWindowBuffer)==168,"64-bit Android buffer ABI");
_Static_assert(offsetof(ANativeWindowBuffer,handle)==96,"Android handle offset");
struct Cookie { uint32_t sequence; };
struct Geometry { uint8_t response,depth;uint16_t sequence;uint32_t length,root;int16_t x,y;uint16_t width,height,border,pad; };
extern struct Cookie xcb_get_geometry(xcb_connection_t*,uint32_t);
extern struct Geometry *xcb_get_geometry_reply(xcb_connection_t*,struct Cookie,void**);
extern int xcb_connection_has_error(xcb_connection_t*);
struct Buffer { ANativeWindowBuffer native;atomic_int refs;int held; };
struct Owner { struct hybris_icd_window base;xcb_connection_t *connection;uint32_t window;int socket;pthread_mutex_t mutex;struct Buffer *pool[8];unsigned count;uint64_t frames; };
struct BufferDescription { uint32_t width,height,layers,format;uint64_t usage;uint32_t stride,reserved0;uint64_t reserved1; };
static int (*send_buffer)(const struct AHardwareBuffer*,int);
static void (*describe_buffer)(const struct AHardwareBuffer*,struct BufferDescription*);
static pthread_once_t sender_once=PTHREAD_ONCE_INIT;
static void setup_sender(void) {
 void *h=hybris_dlopen("/system/lib64/libnativewindow.so",RTLD_NOW|RTLD_LOCAL);
 if(h){send_buffer=(void*)hybris_dlsym(h,"AHardwareBuffer_sendHandleToUnixSocket");describe_buffer=(void*)hybris_dlsym(h,"AHardwareBuffer_describe");}
}
static void retain(android_native_base_t *b){atomic_fetch_add(&((struct Buffer*)b)->refs,1);}
static void release(android_native_base_t *b){struct Buffer *p=(void*)b;if(atomic_fetch_sub(&p->refs,1)==1){hybris_gralloc_release(p->native.handle,1);free(p);}}
static int fence_wait(int fd){if(fd<0)return 0;struct pollfd p={fd,POLLIN,0};int n;do{n=poll(&p,1,15000);}while(n<0&&errno==EINTR);close(fd);return n>0&&(p.revents&POLLIN)?0:-EIO;}
static void drop_pool(struct Owner *o){for(unsigned i=0;i<o->count;i++)release(&o->pool[i]->native.common);o->count=0;}
static int configure(struct hybris_icd_window *base,unsigned w,unsigned h,int format,unsigned usage,unsigned count){
 struct Owner *o=(void*)base;if(!w||!h||w>8192||h>8192||count<2||count>8)return -EINVAL;
 /* CPU read/write flags are not a supported frame transport. */
 if(usage&0xff){fprintf(stderr,"REA_SURFACE reject CPU usage=0x%x\n",usage);return -ENOTSUP;}
 pthread_mutex_lock(&o->mutex);if(o->count){pthread_mutex_unlock(&o->mutex);return -EBUSY;}
 int result=0;for(unsigned i=0;i<count;i++){
  struct Buffer *b=calloc(1,sizeof(*b));if(!b){result=-ENOMEM;break;}
  b->native.common.magic=ANDROID_NATIVE_BUFFER_MAGIC;b->native.common.version=sizeof(ANativeWindowBuffer);b->native.common.incRef=retain;b->native.common.decRef=release;atomic_init(&b->refs,1);
  b->native.width=w;b->native.height=h;b->native.format=format;b->native.usage=usage;b->native.usage_deprecated=usage;b->native.layerCount=1;uint32_t stride=0;
  int r=hybris_gralloc_allocate(w,h,format,usage,&b->native.handle,&stride);
  if(r||!hybris_gralloc_get_hardware_buffer(b->native.handle)){if(!r)hybris_gralloc_release(b->native.handle,1);free(b);result=r?r:-ENOTSUP;break;}
  b->native.stride=stride;o->pool[o->count++]=b;
 }
 if(result)drop_pool(o);fprintf(stderr,"REA_SURFACE configure=%ux%u format=%d usage=0x%x count=%u result=%d\n",w,h,format,usage,count,result);pthread_mutex_unlock(&o->mutex);return result;
}
static int dequeue(struct hybris_icd_window *base,int64_t timeout,ANativeWindowBuffer **out,int *fd){
 (void)timeout;struct Owner *o=(void*)base;*out=NULL;*fd=-1;pthread_mutex_lock(&o->mutex);
 for(unsigned i=0;i<o->count;i++)if(!o->pool[i]->held){o->pool[i]->held=1;*out=&o->pool[i]->native;pthread_mutex_unlock(&o->mutex);return 0;}
 pthread_mutex_unlock(&o->mutex);return -EAGAIN; /* queue completes before releasing a slot */
}
static int queue(struct hybris_icd_window *base,ANativeWindowBuffer *native,int fd){
 int result=fence_wait(fd);if(result)return result;struct Owner *o=(void*)base;struct Buffer *b=(void*)native;
 struct AHardwareBuffer *ahb=hybris_gralloc_get_hardware_buffer(native->handle);if(!ahb)return -ENOTSUP;
 if(!o->frames&&describe_buffer){struct BufferDescription desc={0};describe_buffer(ahb,&desc);fprintf(stderr,"REA_SURFACE buffer native-format=%d ahb-format=%u stride=%u usage=0x%llx\n",native->format,desc.format,desc.stride,(unsigned long long)desc.usage);}
 pthread_mutex_lock(&o->mutex);
 if(o->socket<0){
  o->socket=socket(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0);struct sockaddr_un addr={.sun_family=AF_UNIX};strcpy(addr.sun_path,getenv("REA_DIRECT_SOCKET"));
  if(o->socket<0||connect(o->socket,(void*)&addr,sizeof(addr))){if(o->socket>=0)close(o->socket);o->socket=-1;pthread_mutex_unlock(&o->mutex);return -EPIPE;}
  /* A background-paused owner may wait indefinitely; its own process teardown cancels IPC. */
  struct timeval t={.tv_sec=0};setsockopt(o->socket,SOL_SOCKET,SO_RCVTIMEO,&t,sizeof(t));setsockopt(o->socket,SOL_SOCKET,SO_SNDTIMEO,&t,sizeof(t));
 }
 result=send_buffer(ahb,o->socket);char ack=0;
 if(!result&&recv(o->socket,&ack,1,0)!=1)result=-EPIPE;if(!result&&ack!=1)result=-EIO;
 if(!result){b->held=0;o->frames++;if(o->frames==1||o->frames%300==0)fprintf(stderr,"REA_SURFACE presented=%llu extent=%dx%d cpu-map=0 readpixels=0\n",(unsigned long long)o->frames,native->width,native->height);}
 else fprintf(stderr,"REA_SURFACE queue-error=%d errno=%d\n",result,errno);
 pthread_mutex_unlock(&o->mutex);return result;
}
static int cancel(struct hybris_icd_window *base,ANativeWindowBuffer *native,int fd){int result=fence_wait(fd);struct Owner *o=(void*)base;pthread_mutex_lock(&o->mutex);((struct Buffer*)native)->held=0;pthread_mutex_unlock(&o->mutex);return result;}
static void disconnect(struct hybris_icd_window *base){struct Owner *o=(void*)base;pthread_mutex_lock(&o->mutex);drop_pool(o);pthread_mutex_unlock(&o->mutex);}
static void destroy(struct hybris_icd_window *base){struct Owner *o=(void*)base;disconnect(base);if(o->socket>=0)close(o->socket);pthread_mutex_destroy(&o->mutex);free(o);}
static int extent(struct hybris_icd_window *base,uint32_t *w,uint32_t *h){
 struct Owner *o=(void*)base;void *error=NULL;struct Geometry *g=xcb_get_geometry_reply(o->connection,xcb_get_geometry(o->connection,o->window),&error);int result=!g||error?-EPIPE:0;if(!result){*w=g->width;*h=g->height;}free(g);free(error);return result;
}
static const struct hybris_icd_window_ops ops={configure,dequeue,queue,cancel,disconnect,destroy,extent};
int hybris_icd_xcb_supported(xcb_connection_t *connection,uint32_t visual){
 (void)visual;const char *path=getenv("REA_DIRECT_SOCKET");struct sockaddr_un addr={.sun_family=AF_UNIX};
 return connection&&!xcb_connection_has_error(connection)&&path&&strlen(path)<sizeof(addr.sun_path)&&access(path,F_OK)==0;
}
int hybris_icd_window_xcb(xcb_connection_t *connection,uint32_t window,struct hybris_icd_window **out){
 *out=NULL;if(!hybris_icd_xcb_supported(connection,0))return -ENOTSUP;pthread_once(&sender_once,setup_sender);if(!send_buffer)return -ENOSYS;
 struct Owner *o=calloc(1,sizeof(*o));if(!o)return -ENOMEM;o->socket=-1;
 o->base.ops=&ops;o->connection=connection;o->window=window;pthread_mutex_init(&o->mutex,NULL);*out=&o->base;fprintf(stderr,"REA_SURFACE native-window ready window=%u\n",window);return 0;
}
int hybris_icd_window_wayland(struct wl_display *display,struct wl_surface *surface,struct hybris_icd_window **out){(void)display;(void)surface;*out=NULL;return -ENOTSUP;}
