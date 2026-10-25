/* Regression for destination-color preservation during transparent blending.
 * GNU AArch64 process, using the launcher's already-running private X server.
 * Readback is diagnostic only; the game presentation path does not use it. */
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <GL/glcorearb.h>
#define GLFN(type, name) type name = (void *)proc(#name)

static unsigned grid_color(unsigned x, unsigned y) { return (x / 4 + 3 * (y / 4)) % 4; }
static const unsigned char grid_rgb[4][4] = {{255,0,0,255}, {0,255,0,255}, {0,0,255,255}, {255,255,255,255}};
static int check_grid(PFNGLREADPIXELSPROC read_pixels, const char *label, int patched) {
    unsigned char pixels[64 * 64 * 4];
    read_pixels(0, 0, 64, 64, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    unsigned errors = 0;
    for (unsigned y = 0; y < 64; y++) for (unsigned x = 0; x < 64; x++) {
        unsigned c = patched && x >= 16 && x < 64 && y >= 16 && y < 40 ? 1 : grid_color(x, y);
        if (memcmp(pixels + (y * 64 + x) * 4, grid_rgb[c], 4)) {
            if (!errors) printf("%s first-mismatch x=%u y=%u actual=%u,%u,%u,%u expected=%u,%u,%u,%u\n", label, x, y,
                pixels[(y*64+x)*4], pixels[(y*64+x)*4+1], pixels[(y*64+x)*4+2], pixels[(y*64+x)*4+3],
                grid_rgb[c][0],grid_rgb[c][1],grid_rgb[c][2],grid_rgb[c][3]);
            errors++;
        }
    }
    printf("%s: mismatch-pixels=%u %s\n", label, errors, errors ? "FAIL" : "PASS");
    return !errors;
}

static int texture_grid_probe(void *(*proc)(const char *), GLuint vertex) {
    GLFN(PFNGLCREATESHADERPROC, glCreateShader); GLFN(PFNGLSHADERSOURCEPROC, glShaderSource);
    GLFN(PFNGLCOMPILESHADERPROC, glCompileShader); GLFN(PFNGLCREATEPROGRAMPROC, glCreateProgram);
    GLFN(PFNGLATTACHSHADERPROC, glAttachShader); GLFN(PFNGLLINKPROGRAMPROC, glLinkProgram);
    GLFN(PFNGLGETPROGRAMIVPROC, glGetProgramiv); GLFN(PFNGLUSEPROGRAMPROC, glUseProgram);
    GLFN(PFNGLGENTEXTURESPROC, glGenTextures); GLFN(PFNGLBINDTEXTUREPROC, glBindTexture);
    GLFN(PFNGLTEXIMAGE2DPROC, glTexImage2D); GLFN(PFNGLTEXPARAMETERIPROC, glTexParameteri);
    GLFN(PFNGLCOMPRESSEDTEXIMAGE2DPROC, glCompressedTexImage2D);
    GLFN(PFNGLCOMPRESSEDTEXSUBIMAGE2DPROC, glCompressedTexSubImage2D);
    GLFN(PFNGLGENBUFFERSPROC, glGenBuffers); GLFN(PFNGLBINDBUFFERPROC, glBindBuffer);
    GLFN(PFNGLBUFFERDATAPROC, glBufferData); GLFN(PFNGLGETERRORPROC, glGetError);
    GLFN(PFNGLDRAWARRAYSPROC, glDrawArrays); GLFN(PFNGLREADPIXELSPROC, glReadPixels);
    GLFN(PFNGLCOPYIMAGESUBDATAPROC, glCopyImageSubData);
    const char *fs = "#version 330 core\nuniform sampler2D image;out vec4 color;void main(){color=texelFetch(image,ivec2(gl_FragCoord.xy),0);}";
    GLuint shader = glCreateShader(GL_FRAGMENT_SHADER), program = glCreateProgram();
    glShaderSource(shader, 1, &fs, NULL); glCompileShader(shader);
    glAttachShader(program, vertex); glAttachShader(program, shader); glLinkProgram(program);
    GLint linked = 0; glGetProgramiv(program, GL_LINK_STATUS, &linked); if (!linked) return 0;
    glUseProgram(program); GLuint texture; glGenTextures(1, &texture); glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    unsigned char rgba[128 * 64 * 4];
    for (unsigned y = 0; y < 64; y++) for (unsigned x = 0; x < 128; x++)
        memcpy(rgba + (y * 128 + x) * 4, grid_rgb[grid_color(x, y)], 4);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 128, 64, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    glDrawArrays(GL_TRIANGLES, 0, 3); int passed = check_grid(glReadPixels, "rgba-grid-upload", 0);
    for (unsigned kind = 0; kind < 2; kind++) {
        unsigned size = kind ? 16 : 8, length = 32 * 16 * size;
        GLenum format = kind ? 0x83f3 : 0x83f1; /* DXT5 RGBA / DXT1 RGBA */
        unsigned char blocks[64 + 32 * 16 * 16] = {0};
        const unsigned short rgb565[] = {0xf800, 0x07e0, 0x001f, 0xffff};
        for (unsigned y = 0; y < 16; y++) for (unsigned x = 0; x < 32; x++) {
            unsigned char *b = blocks + 64 + (y * 32 + x) * size;
            unsigned short c = rgb565[grid_color(x * 4, y * 4)];
            if (kind) {b[0] = b[1] = 255; b += 8;}
            b[0] = b[2] = c & 255; b[1] = b[3] = c >> 8;
        }
        glCompressedTexImage2D(GL_TEXTURE_2D, 0, format, 128, 64, 0, length, blocks + 64);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        passed &= check_grid(glReadPixels, kind ? "bc3-grid-upload" : "bc1-grid-upload", 0);
        unsigned char update[12 * 6 * 16] = {0};
        for (unsigned b = 0; b < 12 * 6; b++) {
            unsigned char *v = update + b * size;
            if (kind) {v[0] = v[1] = 255; v += 8;}
            v[0] = v[2] = 0xe0; v[1] = v[3] = 0x07;
        }
        glCompressedTexSubImage2D(GL_TEXTURE_2D, 0, 16, 16, 48, 24, format, 12 * 6 * size, update);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        passed &= check_grid(glReadPixels, kind ? "bc3-grid-subimage" : "bc1-grid-subimage", 1);
        GLuint pbo; glGenBuffers(1, &pbo); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbo);
        glBufferData(GL_PIXEL_UNPACK_BUFFER, length + 64, blocks, GL_STATIC_DRAW);
        glCompressedTexImage2D(GL_TEXTURE_2D, 0, format, 128, 64, 0, length, (void *)64);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0); glDrawArrays(GL_TRIANGLES, 0, 3);
        passed &= check_grid(glReadPixels, kind ? "bc3-grid-pbo" : "bc1-grid-pbo", 0);
        GLuint source; glGenTextures(1, &source); glBindTexture(GL_TEXTURE_2D, source);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, kind ? GL_RGBA32UI : GL_RG32UI, 32, 16, 0,
                     kind ? GL_RGBA_INTEGER : GL_RG_INTEGER, GL_UNSIGNED_INT, blocks + 64);
        glBindTexture(GL_TEXTURE_2D, texture);
        unsigned char blank[32 * 16 * 16] = {0};
        glCompressedTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 64, format, length, blank);
        glCopyImageSubData(source, GL_TEXTURE_2D, 0, 0, 0, 0, texture, GL_TEXTURE_2D, 0, 0, 0, 0, 32, 16, 1);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        passed &= check_grid(glReadPixels, kind ? "bc3-uint-copy-image" : "bc1-uint-copy-image", 0);
    }
    for (unsigned channels = 1; channels <= 2; channels++) {
        unsigned char blocks[32 * 16 * 16] = {0};
        for (unsigned y = 0; y < 16; y++) for (unsigned x = 0; x < 32; x++) {
            unsigned char *b = blocks + (y * 32 + x) * channels * 8;
            b[0] = b[1] = ((x + y) % 4) * 85;
            if (channels == 2) b[8] = b[9] = ((x + 2 * y) % 4) * 85;
        }
        glBindTexture(GL_TEXTURE_2D, texture);
        glCompressedTexImage2D(GL_TEXTURE_2D, 0, channels == 1 ? 0x8dbb : 0x8dbd,
            128, 64, 0, 32 * 16 * channels * 8, blocks);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        unsigned char pixels[64 * 64 * 4]; glReadPixels(0, 0, 64, 64, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        unsigned errors = 0;
        for (unsigned y = 0; y < 64; y++) for (unsigned x = 0; x < 64; x++) {
            unsigned char *v = pixels + (y * 64 + x) * 4;
            unsigned r = ((x/4 + y/4) % 4) * 85, g = channels == 1 ? 0 : ((x/4 + 2*(y/4)) % 4) * 85;
            if (v[0] != r || v[1] != g || v[2] || v[3] != 255) {
                if (!errors) printf("bc%u-mask first-mismatch x=%u y=%u actual=%u,%u,%u,%u expected=%u,%u,0,255\n",channels+3,x,y,v[0],v[1],v[2],v[3],r,g);
                errors++;
            }
        }
        printf("bc%u-mask-grid: mismatch-pixels=%u %s\n", channels+3, errors, errors ? "FAIL" : "PASS");
        passed &= !errors;
    }
    GLenum error = glGetError(); printf("grid-gl-error=0x%x\n", error);
    return passed && !error;
}

static int texture_array_probe(void *(*proc)(const char *), GLuint vertex) {
    GLFN(PFNGLCREATESHADERPROC, glCreateShader); GLFN(PFNGLSHADERSOURCEPROC, glShaderSource);
    GLFN(PFNGLCOMPILESHADERPROC, glCompileShader); GLFN(PFNGLCREATEPROGRAMPROC, glCreateProgram);
    GLFN(PFNGLATTACHSHADERPROC, glAttachShader); GLFN(PFNGLLINKPROGRAMPROC, glLinkProgram);
    GLFN(PFNGLGETPROGRAMIVPROC, glGetProgramiv); GLFN(PFNGLUSEPROGRAMPROC, glUseProgram);
    GLFN(PFNGLGENTEXTURESPROC, glGenTextures); GLFN(PFNGLBINDTEXTUREPROC, glBindTexture);
    GLFN(PFNGLTEXSTORAGE3DPROC, glTexStorage3D); GLFN(PFNGLTEXSUBIMAGE3DPROC, glTexSubImage3D);
    GLFN(PFNGLCOMPRESSEDTEXSUBIMAGE3DPROC, glCompressedTexSubImage3D);
    GLFN(PFNGLTEXPARAMETERIPROC, glTexParameteri); GLFN(PFNGLDRAWARRAYSPROC, glDrawArrays);
    GLFN(PFNGLREADPIXELSPROC, glReadPixels); GLFN(PFNGLGETERRORPROC, glGetError);
    const char *fs = "#version 330 core\nuniform sampler2DArray image;out vec4 color;void main(){color=texelFetch(image,ivec3(ivec2(gl_FragCoord.xy),2),0);}";
    GLuint shader = glCreateShader(GL_FRAGMENT_SHADER), program = glCreateProgram();
    glShaderSource(shader, 1, &fs, NULL); glCompileShader(shader);
    glAttachShader(program, vertex); glAttachShader(program, shader); glLinkProgram(program);
    GLint linked = 0; glGetProgramiv(program, GL_LINK_STATUS, &linked); if (!linked) return 0;
    glUseProgram(program); int passed = 1;
    for (unsigned kind = 0; kind < 3; kind++) {
        GLuint texture; glGenTextures(1, &texture); glBindTexture(GL_TEXTURE_2D_ARRAY, texture);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        GLenum format = kind == 0 ? GL_RGBA8 : kind == 1 ? 0x83f1 : 0x83f3;
        glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, format, 128, 64, 3);
        if (!kind) {
            unsigned char rgba[128 * 64 * 3 * 4] = {0};
            for (unsigned z = 0; z < 3; z++) for (unsigned y = 0; y < 64; y++) for (unsigned x = 0; x < 128; x++) {
                unsigned char *v = rgba + ((z * 64 + y) * 128 + x) * 4;
                v[3] = 255; if (z == 2) memcpy(v, grid_rgb[grid_color(x, y)], 4);
            }
            glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, 128, 64, 3, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        } else {
            unsigned size = kind == 1 ? 8 : 16;
            unsigned char blocks[32 * 16 * 3 * 16] = {0};
            const unsigned short rgb565[] = {0xf800, 0x07e0, 0x001f, 0xffff};
            for (unsigned z = 0; z < 3; z++) for (unsigned y = 0; y < 16; y++) for (unsigned x = 0; x < 32; x++) {
                unsigned char *b = blocks + ((z * 16 + y) * 32 + x) * size;
                unsigned short c = z == 2 ? rgb565[grid_color(x * 4, y * 4)] : 0;
                if (kind == 2) {b[0] = b[1] = 255; b += 8;}
                b[0] = b[2] = c & 255; b[1] = b[3] = c >> 8;
            }
            glCompressedTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, 128, 64, 3, format, 32 * 16 * 3 * size, blocks);
        }
        glDrawArrays(GL_TRIANGLES, 0, 3);
        passed &= check_grid(glReadPixels, kind == 0 ? "rgba-array-storage-upload" : kind == 1 ? "bc1-array-storage-upload" : "bc3-array-storage-upload", 0);
    }
    GLenum error = glGetError(); printf("array-gl-error=0x%x\n", error);
    return passed && !error;
}

static int terrain_inputs_probe(void *(*proc)(const char *)) {
    GLFN(PFNGLCREATESHADERPROC, glCreateShader); GLFN(PFNGLSHADERSOURCEPROC, glShaderSource);
    GLFN(PFNGLCOMPILESHADERPROC, glCompileShader); GLFN(PFNGLCREATEPROGRAMPROC, glCreateProgram);
    GLFN(PFNGLATTACHSHADERPROC, glAttachShader); GLFN(PFNGLLINKPROGRAMPROC, glLinkProgram);
    GLFN(PFNGLGETPROGRAMIVPROC, glGetProgramiv); GLFN(PFNGLUSEPROGRAMPROC, glUseProgram);
    GLFN(PFNGLGETUNIFORMLOCATIONPROC, glGetUniformLocation); GLFN(PFNGLUNIFORM1IPROC, glUniform1i);
    GLFN(PFNGLGETUNIFORMBLOCKINDEXPROC, glGetUniformBlockIndex); GLFN(PFNGLUNIFORMBLOCKBINDINGPROC, glUniformBlockBinding);
    GLFN(PFNGLGENBUFFERSPROC, glGenBuffers); GLFN(PFNGLBINDBUFFERPROC, glBindBuffer);
    GLFN(PFNGLBUFFERDATAPROC, glBufferData); GLFN(PFNGLBINDBUFFERBASEPROC, glBindBufferBase);
    GLFN(PFNGLGENTEXTURESPROC, glGenTextures); GLFN(PFNGLBINDTEXTUREPROC, glBindTexture);
    GLFN(PFNGLACTIVETEXTUREPROC, glActiveTexture); GLFN(PFNGLTEXBUFFERPROC, glTexBuffer);
    GLFN(PFNGLTEXBUFFERRANGEPROC, glTexBufferRange); GLFN(PFNGLTEXIMAGE2DPROC, glTexImage2D);
    GLFN(PFNGLFRAMEBUFFERTEXTURE2DPROC, glFramebufferTexture2D); GLFN(PFNGLDRAWBUFFERSPROC, glDrawBuffers);
    GLFN(PFNGLDRAWARRAYSPROC, glDrawArrays); GLFN(PFNGLREADBUFFERPROC, glReadBuffer);
    GLFN(PFNGLREADPIXELSPROC, glReadPixels); GLFN(PFNGLGETERRORPROC, glGetError);
    const char *vs = "#version 330 core\nlayout(std140)uniform Transform{mat4 projection;};uniform samplerBuffer table;out vec4 sampleValue;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=projection*vec4(p*2.0-1.0,0,1);sampleValue=texelFetch(table,7);}";
    const char *fs = "#version 330 core\nin vec4 sampleValue;layout(location=0)out vec4 color;layout(location=1)out vec4 light;void main(){color=sampleValue;light=vec4(1.0-sampleValue.rgb,1);}";
    GLuint v=glCreateShader(GL_VERTEX_SHADER), f=glCreateShader(GL_FRAGMENT_SHADER), program=glCreateProgram();
    glShaderSource(v,1,&vs,NULL);glCompileShader(v);glShaderSource(f,1,&fs,NULL);glCompileShader(f);
    glAttachShader(program,v);glAttachShader(program,f);glLinkProgram(program);GLint linked=0;
    glGetProgramiv(program,GL_LINK_STATUS,&linked);if(!linked)return 0;glUseProgram(program);
    const float matrix[16]={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};GLuint buffers[2];glGenBuffers(2,buffers);
    glBindBuffer(GL_UNIFORM_BUFFER,buffers[0]);glBufferData(GL_UNIFORM_BUFFER,sizeof(matrix),matrix,GL_STATIC_DRAW);
    glBindBufferBase(GL_UNIFORM_BUFFER,3,buffers[0]);glUniformBlockBinding(program,glGetUniformBlockIndex(program,"Transform"),3);
    float data[256]={0};for(int i=0;i<2;i++){data[i*64+28]=0.25f;data[i*64+29]=0.5f;data[i*64+30]=0.75f;data[i*64+31]=1;}
    glBindBuffer(GL_TEXTURE_BUFFER,buffers[1]);glBufferData(GL_TEXTURE_BUFFER,sizeof(data),data,GL_STATIC_DRAW);
    GLuint texture,light;glGenTextures(1,&texture);glActiveTexture(GL_TEXTURE4);glBindTexture(GL_TEXTURE_BUFFER,texture);
    glTexBuffer(GL_TEXTURE_BUFFER,GL_RGBA32F,buffers[1]);glUniform1i(glGetUniformLocation(program,"table"),4);
    glGenTextures(1,&light);glBindTexture(GL_TEXTURE_2D,light);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,64,64,0,GL_RGBA,GL_UNSIGNED_BYTE,NULL);
    glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT1,GL_TEXTURE_2D,light,0);
    const GLenum targets[]={GL_COLOR_ATTACHMENT0,GL_COLOR_ATTACHMENT1};glDrawBuffers(2,targets);
    int passed=1;
    for(int range=0;range<6;range++){
        if(range==2)glTexBufferRange(GL_TEXTURE_BUFFER,GL_RGBA32F,buffers[1],0,1024);
        if(range==3)glTexBufferRange(GL_TEXTURE_BUFFER,GL_RGBA32F,buffers[1],0,512);
        if(range==4)glTexBufferRange(GL_TEXTURE_BUFFER,GL_RGBA32F,buffers[1],256,512);
        if(range==5)glTexBuffer(GL_TEXTURE_BUFFER,GL_RGBA32F,buffers[1]);
        glDrawArrays(GL_TRIANGLES,0,3);
        for(int attachment=0;attachment<2;attachment++){
            unsigned char pixel[4];glReadBuffer(targets[attachment]);glReadPixels(32,32,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
            int expected[4]={attachment?191:64,128,attachment?64:191,255};
            int ok=1;for(int c=0;c<4;c++)if(pixel[c]<expected[c]-1||pixel[c]>expected[c]+1)ok=0;
            printf("vertex-buffer-ubo-mrt range=%d attachment=%d actual=%u,%u,%u,%u %s\n",range,attachment,pixel[0],pixel[1],pixel[2],pixel[3],ok?"PASS":"FAIL");passed&=ok;
        }
    }
    glReadBuffer(GL_COLOR_ATTACHMENT0);glDrawBuffers(1,targets);glActiveTexture(GL_TEXTURE0);
    GLenum error=glGetError();printf("terrain-inputs-gl-error=0x%x\n",error);return passed&&!error;
}

int main(int argc, char **argv) {
    int window_probe = argc == 2 && !strcmp(argv[1], "--window");
    setbuf(stdout, NULL);
    setbuf(stderr, NULL);
    void *x = dlopen("libX11.so.6", RTLD_NOW), *e = dlopen("libEGL.so.1", RTLD_NOW);
    if (!x || !e) {fprintf(stderr, "Probe library load failed: %s\n", dlerror()); return 2;}
    void *(*open_display)(const char *) = (void *)dlsym(x, "XOpenDisplay");
    void *(*get_display)(void *) = (void *)dlsym(e, "eglGetDisplay");
    unsigned (*initialize)(void *, int *, int *) = (void *)dlsym(e, "eglInitialize");
    unsigned (*bind_api)(unsigned) = (void *)dlsym(e, "eglBindAPI");
    unsigned (*choose)(void *, const int *, void **, int, int *) = (void *)dlsym(e, "eglChooseConfig");
    void *(*create_context)(void *, void *, void *, const int *) = (void *)dlsym(e, "eglCreateContext");
    void *(*create_pbuffer)(void *, void *, const int *) = (void *)dlsym(e, "eglCreatePbufferSurface");
    unsigned (*make_current)(void *, void *, void *, void *) = (void *)dlsym(e, "eglMakeCurrent");
    void *(*proc)(const char *) = (void *)dlsym(e, "eglGetProcAddress");
    unsigned (*egl_error)(void) = (void *)dlsym(e, "eglGetError");
    unsigned (*swap_interval)(void *, int) = (void *)dlsym(e, "eglSwapInterval");
    void *xd = open_display(NULL);
    if (!xd) {fprintf(stderr, "Private X server is unavailable\n"); return 2;}
    void *display = get_display(xd), *config = NULL;
    int major, minor, count = 0;
    const int config_attrs[] = {0x3033, window_probe ? 4 : 1, 0x3040, 8, 0x3038};
    const int context_attrs[] = {0x3098, 3, 0x30fb, 3, 0x30fd, 1, 0x3038};
    const int surface_attrs[] = {0x3057, 64, 0x3056, 64, 0x3038};
    if (!initialize(display, &major, &minor)) {
        fprintf(stderr, "eglInitialize failed: 0x%x\n", egl_error()); return 2;
    }
    printf("EGL initialized: %d.%d\n", major, minor);
    if (!bind_api(0x30a2) || !choose(display, config_attrs, &config, 1, &count) || !count) {
        fprintf(stderr, "OpenGL config unavailable: 0x%x\n", egl_error()); return 2;
    }
    void *context = create_context(display, config, NULL, context_attrs);
    if (!context) {
        fprintf(stderr, "OpenGL 3.3 context failed: 0x%x\n", egl_error()); return 2;
    }
    void *surface;
    if (window_probe) {
        unsigned long (*root)(void *) = (void *)dlsym(x, "XDefaultRootWindow");
        unsigned long (*create)(void *, unsigned long, int, int, unsigned, unsigned,
                                unsigned, unsigned long, unsigned long) = (void *)dlsym(x, "XCreateSimpleWindow");
        int (*map)(void *, unsigned long) = (void *)dlsym(x, "XMapWindow");
        int (*sync)(void *, int) = (void *)dlsym(x, "XSync");
        void *(*window_surface)(void *, void *, unsigned long, const int *) = (void *)dlsym(e, "eglCreateWindowSurface");
        unsigned long window = create(xd, root(xd), 0, 0, 640, 360, 0, 0, 0);
        map(xd, window); sync(xd, 0);
        surface = window_surface(display, config, window, NULL);
    } else surface = create_pbuffer(display, config, surface_attrs);
    if (!surface || !make_current(display, surface, surface, context)) {
        fprintf(stderr, "OpenGL pbuffer/make-current failed: 0x%x\n", egl_error()); return 2;
    }
    if (!swap_interval(display, 1)) {
        fprintf(stderr, "Pbuffer swap interval failed: 0x%x\n", egl_error()); return 2;
    }
    puts("Surface swap interval: PASS");
    GLFN(PFNGLGETSTRINGPROC, glGetString);
    printf("OpenGL version: %s\nRenderer: %s\n", glGetString(GL_VERSION), glGetString(GL_RENDERER));
    GLFN(PFNGLCREATESHADERPROC, glCreateShader); GLFN(PFNGLSHADERSOURCEPROC, glShaderSource);
    GLFN(PFNGLCOMPILESHADERPROC, glCompileShader); GLFN(PFNGLCREATEPROGRAMPROC, glCreateProgram);
    GLFN(PFNGLATTACHSHADERPROC, glAttachShader); GLFN(PFNGLLINKPROGRAMPROC, glLinkProgram);
    GLFN(PFNGLGETPROGRAMIVPROC, glGetProgramiv); GLFN(PFNGLUSEPROGRAMPROC, glUseProgram);
    GLFN(PFNGLGETPROGRAMINFOLOGPROC, glGetProgramInfoLog);
    GLFN(PFNGLGETUNIFORMLOCATIONPROC, glGetUniformLocation); GLFN(PFNGLUNIFORM1IPROC, glUniform1i);
    GLFN(PFNGLGENVERTEXARRAYSPROC, glGenVertexArrays); GLFN(PFNGLBINDVERTEXARRAYPROC, glBindVertexArray);
    GLFN(PFNGLGENTEXTURESPROC, glGenTextures); GLFN(PFNGLBINDTEXTUREPROC, glBindTexture);
    GLFN(PFNGLTEXIMAGE2DPROC, glTexImage2D); GLFN(PFNGLGENFRAMEBUFFERSPROC, glGenFramebuffers);
    GLFN(PFNGLTEXPARAMETERIPROC, glTexParameteri); GLFN(PFNGLACTIVETEXTUREPROC, glActiveTexture);
    GLFN(PFNGLBINDFRAMEBUFFERPROC, glBindFramebuffer); GLFN(PFNGLFRAMEBUFFERTEXTURE2DPROC, glFramebufferTexture2D);
    GLFN(PFNGLCHECKFRAMEBUFFERSTATUSPROC, glCheckFramebufferStatus); GLFN(PFNGLVIEWPORTPROC, glViewport);
    GLFN(PFNGLCLEARCOLORPROC, glClearColor); GLFN(PFNGLCLEARPROC, glClear);
    GLFN(PFNGLENABLEPROC, glEnable); GLFN(PFNGLBLENDFUNCSEPARATEPROC, glBlendFuncSeparate);
    GLFN(PFNGLDISABLEPROC, glDisable);
    GLFN(PFNGLDRAWARRAYSPROC, glDrawArrays); GLFN(PFNGLREADPIXELSPROC, glReadPixels);
    GLFN(PFNGLGETERRORPROC, glGetError);
    const char *vs = "#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}";
    const char *fs = "#version 330 core\nout vec4 color;void main(){color=vec4(0,0,1,0.25);}";
    GLuint v = glCreateShader(GL_VERTEX_SHADER), f = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(v, 1, &vs, NULL); glCompileShader(v);
    glShaderSource(f, 1, &fs, NULL); glCompileShader(f);
    GLuint program = glCreateProgram(); glAttachShader(program, v); glAttachShader(program, f);
    glLinkProgram(program); GLint linked = 0; glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        char log[2048]; glGetProgramInfoLog(program, sizeof(log), NULL, log);
        fprintf(stderr, "GLSL program failed: %s\n", log); return 2;
    }
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
    const char *sample_fs = "#version 330 core\nuniform sampler2D image;out vec4 color;void main(){color=texelFetch(image,ivec2(0),0);}";
    GLuint sample_shader = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(sample_shader, 1, &sample_fs, NULL); glCompileShader(sample_shader);
    GLuint sample_program = glCreateProgram();
    glAttachShader(sample_program, v); glAttachShader(sample_program, sample_shader);
    glLinkProgram(sample_program); glGetProgramiv(sample_program, GL_LINK_STATUS, &linked);
    if (!linked) {
        char log[2048]; glGetProgramInfoLog(sample_program, sizeof(log), NULL, log);
        fprintf(stderr, "Texture sampling program failed: %s\n", log); return 2;
    }
    glDisable(GL_BLEND); glUseProgram(sample_program);
    GLuint sampled; glGenTextures(1, &sampled); glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, sampled);
    const unsigned char texel[4] = {64, 128, 192, 255};
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, texel);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glUniform1i(glGetUniformLocation(sample_program, "image"), 0);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glReadPixels(32, 32, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, after);
    error = glGetError();
    int texture_passed = !error && after[0] == 64 && after[1] == 128 && after[2] == 192 && after[3] == 255;
    printf("texture-regression: actual=%u,%u,%u,%u expected=64,128,192,255 gl-error=0x%x %s\n",
           after[0], after[1], after[2], after[3], error, texture_passed ? "PASS" : "FAIL");
    int grid_passed = texture_grid_probe(proc, v);
    int array_passed = texture_array_probe(proc, v);
    int terrain_passed = terrain_inputs_probe(proc);
    if (window_probe) {
        GLFN(PFNGLSCISSORPROC, glScissor);
        unsigned (*swap)(void *, void *) = (void *)dlsym(e, "eglSwapBuffers");
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, 640, 360); glEnable(GL_SCISSOR_TEST);
        for (int frame = 0; frame < 2; frame++) {
            for (int band = 0; band < 3; band++) {
                glScissor(band * 213, 0, band == 2 ? 214 : 213, 360);
                glClearColor(band == 0, band == 1, band == 2, 1);
                glClear(GL_COLOR_BUFFER_BIT);
                glReadPixels(band * 213 + 100, 180, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, after);
                printf("window-band=%d rgba=%u,%u,%u,%u gl-error=0x%x\n", band,
                    after[0], after[1], after[2], after[3], glGetError());
            }
            if (!swap(display, surface)) {fprintf(stderr, "Window swap failed: 0x%x\n", egl_error()); return 2;}
        }
        puts("Window pattern presented: left=red middle=green right=blue; compare Android screenshot");
        sleep(12);
    }
    return passed && texture_passed && grid_passed && array_passed && terrain_passed ? 0 : 1;
}
