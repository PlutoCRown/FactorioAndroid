/* SPDX-License-Identifier: GPL-3.0-or-later
 * Preserve the requested keyboard hint through SDL's public callback API.
 * Does not write engine memory, replace instructions, or change game files.
 */
#define _GNU_SOURCE
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <link.h>
#include <pthread.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/time.h>
#include <arpa/inet.h>
#include "sdl-hint-interfaces.h"

static bool (*set_hint)(const char *,const char *,int);
static bool (*add_callback)(const char *,void (*)(void *,const char *,const char *,const char *),void *);
static const char hint_name[]="SDL_ENABLE_STEAM_SCREEN_KEYBOARD";
static bool (*run_main)(void (*)(void *),void *,bool);
static void (*send_text)(const char *);
static bool (*send_key)(uint64_t,unsigned,unsigned,int,int,bool);
static void *(*keyboard_focus)(void);
static bool (*text_active)(void *);
static bool (*screen_shown)(void *);
static void (*show_screen)(void *,void *,unsigned),(*hide_screen)(void *,void *);
static void **video_device;
static void *(*get_environment)(void);
static bool (*set_environment)(void *,const char *,const char *,bool),(*unset_environment)(void *,const char *);
static char input_socket[91],input_token[37];
static void preserve_hint(void *userdata,const char *name,const char *old_value,const char *new_value) {
    (void)userdata;(void)old_value;
    if(new_value&&!strcmp(new_value,"1"))return;
    /* A changed value is installed before SDL invokes callbacks. The nested
     * notification carries 1 and returns above, so this cannot recurse forever. */
    if(!set_hint(name,"1",2))fprintf(stderr,"REA_IME_HINT reapply_failed=true\n");
}
static bool executable(const struct dl_phdr_info *info,uintptr_t address) {
    for(unsigned i=0;i<info->dlpi_phnum;i++) {
        const ElfW(Phdr) *p=&info->dlpi_phdr[i];
        if(p->p_type==PT_LOAD&&(p->p_flags&(PF_R|PF_X))==(PF_R|PF_X)&&
            address>=p->p_vaddr&&address+16<=p->p_vaddr+p->p_memsz)return true;
    }
    return false;
}
static int bind_engine(struct dl_phdr_info *info,size_t size,void *userdata) {
    (void)size;(void)userdata;
    const char *name=info->dlpi_name?info->dlpi_name:"",*base=strrchr(name,'/');base=base?base+1:name;
    if(*name&&strcmp(base,"factorio"))return 0;
    const uintptr_t offsets[]={IME_SDL_ADD_HINT,IME_SDL_SET_HINT};
    const unsigned char prefix[]={0xfd,0x7b,0xbd,0xa9,0xf5,0x0b,0x00,0xf9,0xf4,0x4f,0x02,0xa9,0xfd,0x03,0x00,0x91};
    for(unsigned i=0;i<2;i++)if(!executable(info,offsets[i])||memcmp((const void *)(info->dlpi_addr+offsets[i]),prefix,16))return 0;
    set_hint=(bool (*)(const char *,const char *,int))(info->dlpi_addr+IME_SDL_SET_HINT);
    add_callback=(bool (*)(const char *,void (*)(void *,const char *,const char *,const char *),void *))(info->dlpi_addr+IME_SDL_ADD_HINT);
    static const unsigned char p_main[]={IME_PREFIX_RUN_MAIN},p_key[]={IME_PREFIX_SEND_KEY},p_text[]={IME_PREFIX_SEND_TEXT},p_focus[]={IME_PREFIX_KEYBOARD_FOCUS},p_active[]={IME_PREFIX_TEXT_ACTIVE},p_shown[]={IME_PREFIX_SCREEN_SHOWN},p_show[]={IME_PREFIX_X11_SHOW},p_hide[]={IME_PREFIX_X11_HIDE},p_env[]={IME_PREFIX_GET_ENV},p_setenv[]={IME_PREFIX_SET_ENV},p_unsetenv[]={IME_PREFIX_UNSET_ENV};
    const struct {uintptr_t offset;const unsigned char *prefix;size_t length;} entries[]={
        {IME_SDL_RUN_MAIN,p_main,sizeof(p_main)},{IME_SDL_SEND_KEY,p_key,sizeof(p_key)},
        {IME_SDL_SEND_TEXT,p_text,sizeof(p_text)},{IME_SDL_KEYBOARD_FOCUS,p_focus,sizeof(p_focus)},
        {IME_SDL_TEXT_ACTIVE,p_active,sizeof(p_active)},{IME_SDL_SCREEN_SHOWN,p_shown,sizeof(p_shown)},
        {IME_X11_SHOW,p_show,sizeof(p_show)},{IME_X11_HIDE,p_hide,sizeof(p_hide)},
        {IME_SDL_GET_ENV,p_env,sizeof(p_env)},{IME_SDL_SET_ENV,p_setenv,sizeof(p_setenv)},{IME_SDL_UNSET_ENV,p_unsetenv,sizeof(p_unsetenv)}};
    for(unsigned i=0;i<sizeof(entries)/sizeof(entries[0]);i++)
        if(!executable(info,entries[i].offset)||memcmp((const void *)(info->dlpi_addr+entries[i].offset),entries[i].prefix,entries[i].length))return 0;
    run_main=(bool (*)(void (*)(void *),void *,bool))(info->dlpi_addr+IME_SDL_RUN_MAIN);
    send_text=(void (*)(const char *))(info->dlpi_addr+IME_SDL_SEND_TEXT);
    send_key=(bool (*)(uint64_t,unsigned,unsigned,int,int,bool))(info->dlpi_addr+IME_SDL_SEND_KEY);
    keyboard_focus=(void *(*)(void))(info->dlpi_addr+IME_SDL_KEYBOARD_FOCUS);
    text_active=(bool (*)(void *))(info->dlpi_addr+IME_SDL_TEXT_ACTIVE);
    screen_shown=(bool (*)(void *))(info->dlpi_addr+IME_SDL_SCREEN_SHOWN);
    show_screen=(void (*)(void *,void *,unsigned))(info->dlpi_addr+IME_X11_SHOW);
    hide_screen=(void (*)(void *,void *))(info->dlpi_addr+IME_X11_HIDE);
    get_environment=(void *(*)(void))(info->dlpi_addr+IME_SDL_GET_ENV);
    set_environment=(bool (*)(void *,const char *,const char *,bool))(info->dlpi_addr+IME_SDL_SET_ENV);
    unset_environment=(bool (*)(void *,const char *))(info->dlpi_addr+IME_SDL_UNSET_ENV);
    for(unsigned i=0;i<info->dlpi_phnum;i++) {
        const ElfW(Phdr) *p=&info->dlpi_phdr[i];
        if(p->p_type==PT_LOAD&&(p->p_flags&PF_R)&&IME_SDL_VIDEO_DEVICE>=p->p_vaddr&&IME_SDL_VIDEO_DEVICE+8<=p->p_vaddr+p->p_memsz)
            video_device=(void **)(info->dlpi_addr+IME_SDL_VIDEO_DEVICE);
    }
    if(!video_device)return 0;
    return 1;
}
struct input_command {int kind,a,b,d;char *text;bool accepted;};
static int editing_scancode(int android_code) {
    switch(android_code){case 67:return 42;case 112:return 76;case 66:return 40;case 61:return 43;
        case 21:return 80;case 22:return 79;case 19:return 82;case 20:return 81;default:return 0;}
}
static void key_pair(int scancode) {
    /* Internal six-argument ABI verified from the exact engine. Ignore held
     * modifiers for IME editing keys; update SDL state and its normal event queue. */
    send_key(0,9u,0,0,scancode,true);send_key(0,9u,0,0,scancode,false);
}
static void cursor_move(int distance) {int scan=distance<0?80:79;for(int i=0;i<abs(distance);i++)key_pair(scan);}
static void apply_input(void *userdata) {
    struct input_command *c=userdata;void *window=keyboard_focus();
    if(!window)return;
    if(c->kind==3) {
        if(c->a&&!text_active(window))return;
        void *device=*video_device,*show=NULL,*hide=NULL;
        if(!device)return;
        memcpy(&show,(char *)device+0x288,sizeof(show));memcpy(&hide,(char *)device+0x290,sizeof(hide));
        if(show!=(void *)show_screen||hide!=(void *)hide_screen)return;
        if(screen_shown(window)!=(bool)c->a) {
            /* The backend emits a URL and a visibility event. Mark its child
             * as an echo so Android keeps the active editor and input mode. */
            void *environment=get_environment();
            if(!environment||!set_environment(environment,"REA_IME_VISIBILITY_ECHO","1",true))return;
            if(c->a)show_screen(device,window,0);else hide_screen(device,window);
            if(!unset_environment(environment,"REA_IME_VISIBILITY_ECHO"))return;
        }
        c->accepted=screen_shown(window)==(bool)c->a;return;
    }
    if(!text_active(window))return;
    if(c->kind==2){key_pair(editing_scancode(c->a));c->accepted=true;return;}
    cursor_move(c->a);for(int i=0;i<c->b;i++)key_pair(42);
    char *start=c->text;
    for(char *p=start;;p++) {
        char value=*p;
        if(value=='\n'||value=='\r'||value=='\t'||value=='\0') {
            *p='\0';if(*start)send_text(start);*p=value;
            if(!value)break;
            if(value!='\r'||p[1]!='\n')key_pair(value=='\t'?43:40);
            start=p+1;
        }
    }
    cursor_move(c->d);c->accepted=true;
}
static bool read_exact(int fd,void *bytes,size_t size) {
    size_t done=0;while(done<size){ssize_t n=read(fd,(char *)bytes+done,size-done);if(n<0&&errno==EINTR)continue;if(n<=0)return false;done+=(size_t)n;}return true;
}
static bool valid_utf8(const unsigned char *s,size_t length) {
    for(size_t i=0;i<length;) {
        unsigned a=s[i++];if(!a)return false;if(a<0x80)continue;
        unsigned n,code,min;if(a>=0xc2&&a<=0xdf){n=1;code=a&31;min=0x80;}
        else if(a>=0xe0&&a<=0xef){n=2;code=a&15;min=0x800;}
        else if(a>=0xf0&&a<=0xf4){n=3;code=a&7;min=0x10000;}else return false;
        if(length-i<n)return false;
        while(n--){unsigned b=s[i++];if((b&0xc0)!=0x80)return false;code=(code<<6)|(b&63);}
        if(code<min||code>0x10ffff||(code>=0xd800&&code<=0xdfff))return false;
    }return true;
}
static void *input_worker(void *unused) {
    (void)unused;int server=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);if(server<0)return NULL;
    struct sockaddr_un address={.sun_family=AF_UNIX};size_t length=strlen(input_socket);
    memcpy(address.sun_path+1,input_socket,length);
    if(bind(server,(struct sockaddr *)&address,offsetof(struct sockaddr_un,sun_path)+1+length)||listen(server,4)){close(server);return NULL;}
    for(;;) {
        int client=accept4(server,NULL,NULL,SOCK_CLOEXEC);if(client<0){if(errno==EINTR)continue;break;}
        struct timeval timeout={2,0};setsockopt(client,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));setsockopt(client,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
        struct ucred peer;socklen_t peer_length=sizeof(peer);uint32_t header[6];char token[36];char *text=NULL;bool accepted=false;
        if(getsockopt(client,SOL_SOCKET,SO_PEERCRED,&peer,&peer_length)||peer.uid!=getuid()||!read_exact(client,header,sizeof(header))||!read_exact(client,token,sizeof(token))||memcmp(token,input_token,36))goto done;
        for(unsigned i=0;i<6;i++)header[i]=ntohl(header[i]);
        struct input_command command={.kind=(int)header[1],.a=(int32_t)header[2],.b=(int32_t)header[3],.d=(int32_t)header[5]};
        if(header[0]!=0x52495431||header[4]>65536)goto done;
        if(command.kind==1){if(command.a< -2048||command.a>2048||command.b<0||command.b>2048||command.d< -2048||command.d>2048)goto done;}
        else if(command.kind==2){if(!editing_scancode(command.a)||command.b||header[4]||command.d)goto done;}
        else if(command.kind==3){if((command.a!=0&&command.a!=1)||command.b||header[4]||command.d)goto done;}else goto done;
        text=malloc((size_t)header[4]+1);if(!text||!read_exact(client,text,header[4])||!valid_utf8((const unsigned char *)text,header[4]))goto done;
        text[header[4]]='\0';command.text=text;
        accepted=run_main(apply_input,&command,true)&&command.accepted;
done:
        send(client,accepted?"OK\n":"NO\n",3,MSG_NOSIGNAL);free(text);close(client);
    }
    close(server);return NULL;
}
__attribute__((constructor)) static void install_hint_guard(void) {
    const char *identity=getenv("REA_IME_HINT_ENGINE_SHA256"),*enabled=getenv(hint_name);
    if(!identity||strcmp(identity,IME_ENGINE_SHA256)||!enabled||strcmp(enabled,"1")||!getenv("REA_IME_TOKEN"))return;
    if(!dl_iterate_phdr(bind_engine,NULL)){fprintf(stderr,"REA_IME_HINT interface_mismatch=true\n");return;}
    bool ready=set_hint(hint_name,"1",2)&&add_callback(hint_name,preserve_hint,NULL);
    fprintf(stderr,"REA_IME_HINT callback_registered=%d engine_files_modified=false\n",ready);
    const char *socket_name=getenv("REA_IME_TEXT_SOCKET"),*token=getenv("REA_IME_TOKEN");
    if(ready&&socket_name&&token&&strlen(socket_name)<=90&&strlen(token)==36) {
        strcpy(input_socket,socket_name);strcpy(input_token,token);
        pthread_t worker;if(!pthread_create(&worker,NULL,input_worker,NULL))pthread_detach(worker);
    }
}
