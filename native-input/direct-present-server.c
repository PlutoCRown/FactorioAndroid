/* Project-owned GPU AHB -> Android Surface receiver. The Unix transport and
 * GLES context outlive SurfaceView recreation. No CPU pixel map/readback.
 * Producer waits its release fence; receiver finishes GPU sampling before ack. */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1
#endif
#include <jni.h>
#include <android/native_window.h>
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#define EXPORT __attribute__((visibility("default")))
typedef unsigned GLuint;typedef unsigned GLenum;typedef int GLint;typedef int GLsizei;typedef unsigned GLbitfield;typedef char GLchar;
static pthread_mutex_t surface_mutex=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t surface_changed=PTHREAD_COND_INITIALIZER;
static ANativeWindow *current_window;
static unsigned long surface_generation;
static int worker_started;
static char socket_path[108],report_path[512];
static void release_window_ref(ANativeWindow *window) {
 void *h=dlopen("libnativewindow.so",RTLD_NOW|RTLD_LOCAL);
 void (*release)(ANativeWindow*)=h?(void*)dlsym(h,"ANativeWindow_release"):NULL;
 if(window&&release)release(window);
}
static ANativeWindow *snapshot_window(unsigned long *generation,void(*acquire)(ANativeWindow*)) {
 pthread_mutex_lock(&surface_mutex);
 while(!current_window)pthread_cond_wait(&surface_changed,&surface_mutex);
 ANativeWindow *window=current_window;acquire(window);*generation=surface_generation;
 pthread_mutex_unlock(&surface_mutex);return window;
}
static void wait_generation(unsigned long generation) {
 pthread_mutex_lock(&surface_mutex);
 while(surface_generation==generation)pthread_cond_wait(&surface_changed,&surface_mutex);
 pthread_mutex_unlock(&surface_mutex);
}
#define FN(type,name) type name=(type)dlsym(egl,#name);if(!name){fprintf(log,"missing=%s\n",#name);goto cleanup;}
#define GLFN(ret,name,args) ret (*name)args=(void*)eglGetProcAddress(#name);if(!name){fprintf(log,"missing=%s\n",#name);goto cleanup;}
static void *render_worker(void *unused) {
 (void)unused;FILE *log=fopen(report_path,"w");if(!log)goto finished;setbuf(log,NULL);fprintf(log,"phase=begin persistent-transport=1\n");
 int server=-1,client=-1;AHardwareBuffer *buffer=NULL;ANativeWindow *window=NULL;unsigned long generation=0;
 EGLDisplay display=EGL_NO_DISPLAY;EGLSurface surface=EGL_NO_SURFACE;EGLContext context=EGL_NO_CONTEXT;EGLImageKHR image=EGL_NO_IMAGE_KHR;
 void *egl=dlopen("libEGL.so",RTLD_NOW|RTLD_LOCAL),*nw=dlopen("libnativewindow.so",RTLD_NOW|RTLD_LOCAL);if(!egl||!nw){fprintf(log,"dlopen-failed=%s\n",dlerror());goto cleanup;}
 void (*acquireWindow)(ANativeWindow*)=(void*)dlsym(nw,"ANativeWindow_acquire");
 void (*releaseWindow)(ANativeWindow*)=(void*)dlsym(nw,"ANativeWindow_release");
 int (*recvBuffer)(int,AHardwareBuffer**)=(void*)dlsym(nw,"AHardwareBuffer_recvHandleFromUnixSocket");
 void (*describe)(const AHardwareBuffer*,AHardwareBuffer_Desc*)=(void*)dlsym(nw,"AHardwareBuffer_describe");
 void (*releaseBuffer)(AHardwareBuffer*)=(void*)dlsym(nw,"AHardwareBuffer_release");
 if(!acquireWindow||!releaseWindow||!recvBuffer||!describe||!releaseBuffer){fprintf(log,"native-interface-error\n");goto cleanup;}
 FN(PFNEGLGETDISPLAYPROC,eglGetDisplay);FN(PFNEGLINITIALIZEPROC,eglInitialize);FN(PFNEGLCHOOSECONFIGPROC,eglChooseConfig);FN(PFNEGLBINDAPIPROC,eglBindAPI);FN(PFNEGLCREATECONTEXTPROC,eglCreateContext);FN(PFNEGLCREATEWINDOWSURFACEPROC,eglCreateWindowSurface);FN(PFNEGLMAKECURRENTPROC,eglMakeCurrent);FN(PFNEGLGETPROCADDRESSPROC,eglGetProcAddress);FN(PFNEGLSWAPBUFFERSPROC,eglSwapBuffers);FN(PFNEGLGETERRORPROC,eglGetError);FN(PFNEGLDESTROYSURFACEPROC,eglDestroySurface);FN(PFNEGLQUERYSURFACEPROC,eglQuerySurface);
 server=socket(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0);struct sockaddr_un addr={.sun_family=AF_UNIX};strcpy(addr.sun_path,socket_path);unlink(socket_path);
 if(server<0||bind(server,(void*)&addr,sizeof(addr))||listen(server,2)){fprintf(log,"listen-error=%d\n",errno);goto cleanup;}fprintf(log,"ready=1\n");
 display=eglGetDisplay(EGL_DEFAULT_DISPLAY);EGLint major,minor;if(!eglInitialize(display,&major,&minor))goto cleanup;eglBindAPI(EGL_OPENGL_ES_API);
 EGLint attributes[]={EGL_SURFACE_TYPE,EGL_WINDOW_BIT,EGL_RENDERABLE_TYPE,0x40,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE},count;EGLConfig config;if(!eglChooseConfig(display,attributes,&config,1,&count)||count<1)goto cleanup;
 EGLint ca[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};context=eglCreateContext(display,config,EGL_NO_CONTEXT,ca);if(context==EGL_NO_CONTEXT)goto cleanup;
 /* Initial attach may itself race HOME during loading. Wait for a usable
  * generation rather than tearing down the persistent producer connection. */
 for(;;){window=snapshot_window(&generation,acquireWindow);surface=eglCreateWindowSurface(display,config,window,NULL);
  if(surface!=EGL_NO_SURFACE&&eglMakeCurrent(display,surface,surface,context))break;
  fprintf(log,"surface-initial-retry generation=%lu egl=0x%x\n",generation,eglGetError());if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);surface=EGL_NO_SURFACE;releaseWindow(window);window=NULL;wait_generation(generation);
 }
 GLFN(const unsigned char*,glGetString,(GLenum));GLFN(GLuint,glCreateShader,(GLenum));GLFN(void,glShaderSource,(GLuint,GLsizei,const GLchar*const*,const GLint*));GLFN(void,glCompileShader,(GLuint));GLFN(void,glGetShaderiv,(GLuint,GLenum,GLint*));GLFN(void,glGetShaderInfoLog,(GLuint,GLsizei,GLsizei*,GLchar*));GLFN(GLuint,glCreateProgram,(void));GLFN(void,glAttachShader,(GLuint,GLuint));GLFN(void,glLinkProgram,(GLuint));GLFN(void,glGetProgramiv,(GLuint,GLenum,GLint*));GLFN(void,glUseProgram,(GLuint));GLFN(void,glGenTextures,(GLsizei,GLuint*));GLFN(void,glBindTexture,(GLenum,GLuint));GLFN(void,glTexParameteri,(GLenum,GLenum,GLint));GLFN(void,glViewport,(GLint,GLint,GLsizei,GLsizei));GLFN(void,glDrawArrays,(GLenum,GLint,GLsizei));GLFN(void,glFinish,(void));GLFN(GLenum,glGetError,(void));
 fprintf(log,"consumer-renderer=%s\nconsumer-version=%s\n",glGetString(0x1f01),glGetString(0x1f02));
 PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getNative=(void*)eglGetProcAddress("eglGetNativeClientBufferANDROID");PFNEGLCREATEIMAGEKHRPROC createImage=(void*)eglGetProcAddress("eglCreateImageKHR");PFNEGLDESTROYIMAGEKHRPROC destroyImage=(void*)eglGetProcAddress("eglDestroyImageKHR");void (*imageTarget)(GLenum,void*)=(void*)eglGetProcAddress("glEGLImageTargetTexture2DOES");if(!getNative||!createImage||!destroyImage||!imageTarget)goto cleanup;
 const char *vs="#version 300 es\nprecision highp float;out vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=vec2(p.x,1.0-p.y);gl_Position=vec4(p*2.0-1.0,0,1);}";
 const char *fs="#version 300 es\nprecision highp float;in vec2 uv;uniform sampler2D image;out vec4 outColor;void main(){outColor=texture(image,uv);}";
 GLuint v=glCreateShader(0x8b31),f=glCreateShader(0x8b30);glShaderSource(v,1,&vs,NULL);glShaderSource(f,1,&fs,NULL);glCompileShader(v);glCompileShader(f);GLint compiled=0;glGetShaderiv(v,0x8b81,&compiled);if(!compiled)goto cleanup;glGetShaderiv(f,0x8b81,&compiled);if(!compiled)goto cleanup;
 GLuint program=glCreateProgram();glAttachShader(program,v);glAttachShader(program,f);glLinkProgram(program);GLint linked=0;glGetProgramiv(program,0x8b82,&linked);if(!linked)goto cleanup;glUseProgram(program);GLuint texture;glGenTextures(1,&texture);glBindTexture(0x0de1,texture);glTexParameteri(0x0de1,0x2801,0x2600);glTexParameteri(0x0de1,0x2800,0x2600);glTexParameteri(0x0de1,0x2802,0x812f);glTexParameteri(0x0de1,0x2803,0x812f);
 unsigned long frames=0;
 for(;;){
  if(client<0){client=accept4(server,NULL,NULL,SOCK_CLOEXEC);if(client<0){if(errno==EINTR)continue;goto cleanup;}fprintf(log,"producer-connected\n");}
  /* No receive timeout: the owned engine is intentionally SIGSTOP-paused while
   * backgrounded. Window-process teardown closes this socket on session end. */
  int rc=recvBuffer(client,&buffer);if(rc||!buffer){fprintf(log,"producer-disconnected=%d\n",rc);close(client);client=-1;continue;}
  AHardwareBuffer_Desc desc={0};describe(buffer,&desc);if(!desc.width||!desc.height||desc.width>8192||desc.height>8192||(desc.usage&0xff)){fprintf(log,"unexpected-image\n");goto cleanup;}
  unsigned long next_generation;ANativeWindow *next=snapshot_window(&next_generation,acquireWindow);
  if(next_generation!=generation){
   eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);releaseWindow(window);window=next;generation=next_generation;
   surface=eglCreateWindowSurface(display,config,window,NULL);
   if(surface==EGL_NO_SURFACE||!eglMakeCurrent(display,surface,surface,context)){
    fprintf(log,"surface-rebind-retry generation=%lu egl=0x%x\n",generation,eglGetError());
    /* Buffer not sampled yet: retain it until a newer visible window exists. */
    wait_generation(generation);releaseBuffer(buffer);buffer=NULL;char consumed=1;if(send(client,&consumed,1,MSG_NOSIGNAL)!=1){close(client);client=-1;}continue;
   }
   fprintf(log,"surface-rebound generation=%lu at-frame=%lu\n",generation,frames);
  }else releaseWindow(next);
  EGLint ia[]={EGL_IMAGE_PRESERVED_KHR,EGL_TRUE,EGL_NONE};image=createImage(display,EGL_NO_CONTEXT,EGL_NATIVE_BUFFER_ANDROID,getNative(buffer),ia);if(image==EGL_NO_IMAGE_KHR){fprintf(log,"image-error=0x%x\n",eglGetError());goto cleanup;}
  glBindTexture(0x0de1,texture);imageTarget(0x0de1,image);EGLint width=0,height=0;eglQuerySurface(display,surface,EGL_WIDTH,&width);eglQuerySurface(display,surface,EGL_HEIGHT,&height);glViewport(0,0,width,height);glDrawArrays(0x0004,0,3);glFinish();unsigned error=glGetError();EGLBoolean swapped=eglSwapBuffers(display,surface);EGLint egl_error=eglGetError();
  pthread_mutex_lock(&surface_mutex);int withdrawn=surface_generation!=generation||!current_window;pthread_mutex_unlock(&surface_mutex);
  int recreatable=(!swapped||error)&&(withdrawn||(egl_error==EGL_BAD_SURFACE||egl_error==EGL_BAD_NATIVE_WINDOW||egl_error==EGL_BAD_CURRENT_SURFACE));
  char ack=((!error&&swapped)||recreatable)?1:0;
  if(!swapped||error||frames%300==0)fprintf(log,"frame=%lu generation=%lu image=%ux%u surface=%dx%d gl-error=0x%x swap=%d egl-error=0x%x cpu-map=0 readpixels=0\n",frames,generation,desc.width,desc.height,width,height,error,swapped,egl_error);
  destroyImage(display,image);image=EGL_NO_IMAGE_KHR;releaseBuffer(buffer);buffer=NULL;
  if(send(client,&ack,1,MSG_NOSIGNAL)!=1){close(client);client=-1;}else if(!ack)goto cleanup;frames++;
  /* Sampling completed before ack, even if Android withdrew the window. Do
   * not signal WSI surface loss for that lifecycle event; await replacement. */
  if(recreatable)wait_generation(generation);
 }
cleanup:
 if(client>=0)close(client);if(server>=0)close(server);unlink(socket_path);
 if(egl){PFNEGLGETPROCADDRESSPROC gp=(void*)dlsym(egl,"eglGetProcAddress");PFNEGLDESTROYIMAGEKHRPROC di=gp?(void*)gp("eglDestroyImageKHR"):NULL;if(di&&image!=EGL_NO_IMAGE_KHR)di(display,image);PFNEGLMAKECURRENTPROC mc=(void*)dlsym(egl,"eglMakeCurrent");if(mc&&display!=EGL_NO_DISPLAY)mc(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);PFNEGLDESTROYSURFACEPROC ds=(void*)dlsym(egl,"eglDestroySurface");if(ds&&surface!=EGL_NO_SURFACE)ds(display,surface);PFNEGLDESTROYCONTEXTPROC dc=(void*)dlsym(egl,"eglDestroyContext");if(dc&&context!=EGL_NO_CONTEXT)dc(display,context);PFNEGLTERMINATEPROC t=(void*)dlsym(egl,"eglTerminate");if(t&&display!=EGL_NO_DISPLAY)t(display);}
 if(nw){void(*rb)(AHardwareBuffer*)=(void*)dlsym(nw,"AHardwareBuffer_release");if(rb&&buffer)rb(buffer);}release_window_ref(window);fclose(log);
finished:
 pthread_mutex_lock(&surface_mutex);worker_started=0;pthread_mutex_unlock(&surface_mutex);return NULL;
}
EXPORT void Java_org_factorio_local_launcher_DirectPresentActivity_present(JNIEnv *env,jclass clazz,jobject javaSurface,jstring javaSocket,jstring javaReport) {
 (void)clazz;void *android=dlopen("libandroid.so",RTLD_NOW|RTLD_LOCAL);ANativeWindow *(*fromSurface)(JNIEnv*,jobject)=android?(void*)dlsym(android,"ANativeWindow_fromSurface"):NULL;if(!fromSurface)return;
 ANativeWindow *next=fromSurface(env,javaSurface);if(!next)return;
 const char *path=(*env)->GetStringUTFChars(env,javaSocket,NULL),*report=(*env)->GetStringUTFChars(env,javaReport,NULL);
 pthread_mutex_lock(&surface_mutex);ANativeWindow *old=current_window;current_window=next;surface_generation++;
 if(!worker_started&&strlen(path)<sizeof(socket_path)&&strlen(report)<sizeof(report_path)){strcpy(socket_path,path);strcpy(report_path,report);pthread_t thread;worker_started=1;if(pthread_create(&thread,NULL,render_worker,NULL)){worker_started=0;}else pthread_detach(thread);}
 pthread_cond_broadcast(&surface_changed);pthread_mutex_unlock(&surface_mutex);release_window_ref(old);
 (*env)->ReleaseStringUTFChars(env,javaSocket,path);(*env)->ReleaseStringUTFChars(env,javaReport,report);
}
EXPORT void Java_org_factorio_local_launcher_DirectPresentActivity_detach(JNIEnv *env,jclass clazz) {
 (void)env;(void)clazz;pthread_mutex_lock(&surface_mutex);ANativeWindow *old=current_window;current_window=NULL;surface_generation++;pthread_cond_broadcast(&surface_changed);pthread_mutex_unlock(&surface_mutex);release_window_ref(old);
}
