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
#include "../sdl-symbols.h"

static bool (*set_hint)(const char *,const char *,int);
static bool (*add_callback)(const char *,void (*)(void *,const char *,const char *,const char *),void *);
static const char hint_name[]="SDL_ENABLE_STEAM_SCREEN_KEYBOARD";
static bool (*run_main)(void (*)(void *),void *,bool);
static void (*send_text)(const char *);
/* Stable SDL3 public event ABI; no internal six-argument keyboard function. */
struct keyboard_event {uint32_t type,reserved;uint64_t timestamp;uint32_t window,which,scancode,key;uint16_t mod,raw;bool down,repeat;};
union keyboard_packet {struct keyboard_event key;unsigned char padding[128];};
_Static_assert(sizeof(union keyboard_packet)==128,"SDL3 event ABI");
_Static_assert(offsetof(struct keyboard_event,down)==36,"SDL3 keyboard ABI");
static bool (*push_event)(void *);
static uint32_t (*window_id)(void *),(*key_code)(int,uint16_t,bool);
static uint64_t (*ticks_ns)(void);
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
static bool bind_interfaces(void) {
    if(!rea_sdl3())return false;
#define BIND(variable, type, name) do { variable=(type)rea_sdl_symbol(name);if(!variable)return false; } while(0)
    BIND(set_hint,bool (*)(const char *,const char *,int),"SDL_SetHintWithPriority");
    BIND(add_callback,bool (*)(const char *,void (*)(void *,const char *,const char *,const char *),void *),"SDL_AddHintCallback");
    BIND(run_main,bool (*)(void (*)(void *),void *,bool),"SDL_RunOnMainThread");
    BIND(send_text,void (*)(const char *),"SDL_SendKeyboardText");
    BIND(push_event,bool (*)(void *),"SDL_PushEvent");
    BIND(window_id,uint32_t (*)(void *),"SDL_GetWindowID");
    BIND(key_code,uint32_t (*)(int,uint16_t,bool),"SDL_GetKeyFromScancode");
    BIND(ticks_ns,uint64_t (*)(void),"SDL_GetTicksNS");
    BIND(keyboard_focus,void *(*)(void),"SDL_GetKeyboardFocus");
    BIND(text_active,bool (*)(void *),"SDL_TextInputActive");
    BIND(screen_shown,bool (*)(void *),"SDL_ScreenKeyboardShown");
    BIND(show_screen,void (*)(void *,void *,unsigned),"X11_ShowScreenKeyboard");
    BIND(hide_screen,void (*)(void *,void *),"X11_HideScreenKeyboard");
    video_device=rea_sdl_video_pointer();if(!video_device)return false;
    BIND(get_environment,void *(*)(void),"SDL_GetEnvironment");
    BIND(set_environment,bool (*)(void *,const char *,const char *,bool),"SDL_SetEnvironmentVariable");
    BIND(unset_environment,bool (*)(void *,const char *),"SDL_UnsetEnvironmentVariable");
#undef BIND
    return true;
}
struct input_command {int kind,a,b,d;char *text;bool accepted;};
static int editing_scancode(int android_code) {
    switch(android_code){case 67:return 42;case 112:return 76;case 66:return 40;case 61:return 43;
        case 21:return 80;case 22:return 79;case 19:return 82;case 20:return 81;default:return 0;}
}
static bool key_pair(int scancode) {
    void *window=keyboard_focus();if(!window)return false;
    union keyboard_packet event={0};
    event.key.window=window_id(window);event.key.scancode=(uint32_t)scancode;
    event.key.key=key_code(scancode,0,true);event.key.timestamp=ticks_ns();
    event.key.type=0x300;event.key.down=true;bool down=push_event(&event);
    event.key.type=0x301;event.key.down=false;event.key.timestamp=ticks_ns();bool up=push_event(&event);
    return down&&up;
}
static bool cursor_move(int distance) {int scan=distance<0?80:79;for(int i=0;i<abs(distance);i++)if(!key_pair(scan))return false;return true;}
static void apply_input(void *userdata) {
    struct input_command *c=userdata;void *window=keyboard_focus();
    if(!window)return;
    if(c->kind==3) {
        if(c->a&&!text_active(window))return;
        void *device=*video_device;
        if(!device)return;
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
    if(c->kind==2){c->accepted=key_pair(editing_scancode(c->a));return;}
    if(!cursor_move(c->a))return;for(int i=0;i<c->b;i++)if(!key_pair(42))return;
    char *start=c->text;
    for(char *p=start;;p++) {
        char value=*p;
        if(value=='\n'||value=='\r'||value=='\t'||value=='\0') {
            *p='\0';if(*start)send_text(start);*p=value;
            if(!value)break;
            if((value!='\r'||p[1]!='\n')&&!key_pair(value=='\t'?43:40))return;
            start=p+1;
        }
    }
    c->accepted=cursor_move(c->d);
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
        else if(command.kind==3){if((command.a!=0&&command.a!=1)||command.b||header[4]||command.d)goto done;}
        else if(command.kind==4){if(command.a||command.b||header[4]||command.d)goto done;}else goto done;
        if(command.kind==4){send(client,"OK\n",3,MSG_NOSIGNAL);close(client);continue;}
        text=malloc((size_t)header[4]+1);if(!text||!read_exact(client,text,header[4])||!valid_utf8((const unsigned char *)text,header[4]))goto done;
        text[header[4]]='\0';command.text=text;
        accepted=run_main(apply_input,&command,true)&&command.accepted;
done:
        send(client,accepted?"OK\n":"NO\n",3,MSG_NOSIGNAL);free(text);close(client);
    }
    close(server);return NULL;
}
__attribute__((constructor)) static void install_hint_guard(void) {
    const char *enabled=getenv(hint_name);
    if(!enabled||strcmp(enabled,"1")||!getenv("REA_IME_TOKEN"))return;
    if(!bind_interfaces()){fprintf(stderr,"REA_IME_HINT interface_mismatch=true\n");return;}
    bool ready=set_hint(hint_name,"1",2)&&add_callback(hint_name,preserve_hint,NULL);
    fprintf(stderr,"REA_IME_HINT callback_registered=%d engine_files_modified=false\n",ready);
    const char *socket_name=getenv("REA_IME_TEXT_SOCKET"),*token=getenv("REA_IME_TOKEN");
    if(ready&&socket_name&&token&&strlen(socket_name)<=90&&strlen(token)==36) {
        strcpy(input_socket,socket_name);strcpy(input_token,token);
        pthread_t worker;if(!pthread_create(&worker,NULL,input_worker,NULL))pthread_detach(worker);
    }
}
