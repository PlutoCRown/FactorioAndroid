/* Regression for destination-color preservation during transparent blending.
 * GNU AArch64 process, using the launcher's already-running private X server.
 * Readback is diagnostic only; the game presentation path does not use it. */
#include <dlfcn.h>
#include <stdio.h>
#include <GL/glcorearb.h>
#define GLFN(type, name) type name = (void *)proc(#name)

int main(void) {
    void *x = dlopen("libX11.so.6", RTLD_NOW), *e = dlopen("libEGL.so.1", RTLD_NOW);
    if (!x || !e) return 2;
    void *(*open_display)(const char *) = (void *)dlsym(x, "XOpenDisplay");
    void *(*get_display)(void *) = (void *)dlsym(e, "eglGetDisplay");
    unsigned (*initialize)(void *, int *, int *) = (void *)dlsym(e, "eglInitialize");
    unsigned (*bind_api)(unsigned) = (void *)dlsym(e, "eglBindAPI");
    unsigned (*choose)(void *, const int *, void **, int, int *) = (void *)dlsym(e, "eglChooseConfig");
    void *(*create_context)(void *, void *, void *, const int *) = (void *)dlsym(e, "eglCreateContext");
    void *(*create_pbuffer)(void *, void *, const int *) = (void *)dlsym(e, "eglCreatePbufferSurface");
    unsigned (*make_current)(void *, void *, void *, void *) = (void *)dlsym(e, "eglMakeCurrent");
    void *(*proc)(const char *) = (void *)dlsym(e, "eglGetProcAddress");
    void *xd = open_display(NULL);
    if (!xd) {fprintf(stderr, "Private X server is unavailable\n"); return 2;}
    void *display = get_display(xd), *config = NULL;
    int major, minor, count = 0;
    const int config_attrs[] = {0x3033, 1, 0x3040, 8, 0x3038};
    const int context_attrs[] = {0x3098, 3, 0x30fb, 3, 0x30fd, 1, 0x3038};
    const int surface_attrs[] = {0x3057, 64, 0x3056, 64, 0x3038};
    if (!initialize(display, &major, &minor) || !bind_api(0x30a2) ||
        !choose(display, config_attrs, &config, 1, &count) || !count) return 2;
    void *context = create_context(display, config, NULL, context_attrs);
    void *surface = create_pbuffer(display, config, surface_attrs);
    if (!context || !surface || !make_current(display, surface, surface, context)) return 2;
    GLFN(PFNGLCREATESHADERPROC, glCreateShader); GLFN(PFNGLSHADERSOURCEPROC, glShaderSource);
    GLFN(PFNGLCOMPILESHADERPROC, glCompileShader); GLFN(PFNGLCREATEPROGRAMPROC, glCreateProgram);
    GLFN(PFNGLATTACHSHADERPROC, glAttachShader); GLFN(PFNGLLINKPROGRAMPROC, glLinkProgram);
    GLFN(PFNGLGETPROGRAMIVPROC, glGetProgramiv); GLFN(PFNGLUSEPROGRAMPROC, glUseProgram);
    GLFN(PFNGLGENVERTEXARRAYSPROC, glGenVertexArrays); GLFN(PFNGLBINDVERTEXARRAYPROC, glBindVertexArray);
    GLFN(PFNGLGENTEXTURESPROC, glGenTextures); GLFN(PFNGLBINDTEXTUREPROC, glBindTexture);
    GLFN(PFNGLTEXIMAGE2DPROC, glTexImage2D); GLFN(PFNGLGENFRAMEBUFFERSPROC, glGenFramebuffers);
    GLFN(PFNGLBINDFRAMEBUFFERPROC, glBindFramebuffer); GLFN(PFNGLFRAMEBUFFERTEXTURE2DPROC, glFramebufferTexture2D);
    GLFN(PFNGLCHECKFRAMEBUFFERSTATUSPROC, glCheckFramebufferStatus); GLFN(PFNGLVIEWPORTPROC, glViewport);
    GLFN(PFNGLCLEARCOLORPROC, glClearColor); GLFN(PFNGLCLEARPROC, glClear);
    GLFN(PFNGLENABLEPROC, glEnable); GLFN(PFNGLBLENDFUNCSEPARATEPROC, glBlendFuncSeparate);
    GLFN(PFNGLDRAWARRAYSPROC, glDrawArrays); GLFN(PFNGLREADPIXELSPROC, glReadPixels);
    GLFN(PFNGLGETERRORPROC, glGetError);
    const char *vs = "#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}";
    const char *fs = "#version 330 core\nout vec4 color;void main(){color=vec4(0,0,1,0.25);}";
    GLuint v = glCreateShader(GL_VERTEX_SHADER), f = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(v, 1, &vs, NULL); glCompileShader(v);
    glShaderSource(f, 1, &fs, NULL); glCompileShader(f);
    GLuint program = glCreateProgram(); glAttachShader(program, v); glAttachShader(program, f);
    glLinkProgram(program); GLint linked = 0; glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) return 2;
    glUseProgram(program); GLuint vao, texture, framebuffer;
    glGenVertexArrays(1, &vao); glBindVertexArray(vao);
    glGenTextures(1, &texture); glBindTexture(GL_TEXTURE_2D, texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 64, 64, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glGenFramebuffers(1, &framebuffer); glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) return 2;
    glViewport(0, 0, 64, 64); glClearColor(1, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
    unsigned char before[4], after[4];
    glReadPixels(32, 32, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, before);
    glEnable(GL_BLEND); glBlendFuncSeparate(GL_ZERO, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glReadPixels(32, 32, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, after);
    GLenum error = glGetError();
    int passed = !error && before[0] == 255 && before[3] == 255 &&
        after[0] >= 190 && after[0] <= 192 && !after[1] && !after[2] && after[3] == 255;
    printf("blend-regression: before=%u,%u,%u,%u after=%u,%u,%u,%u expected=191,0,0,255 gl-error=0x%x %s\n",
        before[0], before[1], before[2], before[3], after[0], after[1], after[2], after[3], error, passed ? "PASS" : "FAIL");
    return passed ? 0 : 1;
}
