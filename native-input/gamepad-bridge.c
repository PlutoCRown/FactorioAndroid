/* SDL3 input relay, resolved from the loaded engine; never patches the engine. */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <pthread.h>
#include <link.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <poll.h>
#include "sdl-symbols.h"

#if __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error This bridge targets a little-endian SDL3 ABI
#endif

/* Public SDL3 virtual descriptor ABI; all optional callbacks remain null. */
struct virtual_desc {
    uint32_t version;
    uint16_t type, padding, vendor_id, product_id, naxes, nbuttons, nballs, nhats;
    uint16_t ntouchpads, nsensors, padding2[2];
    uint32_t button_mask, axis_mask;
    const char *name;
    const void *touchpads, *sensors;
    void *userdata;
    void (*update)(void *);
    void (*set_player_index)(void *, int);
    bool (*rumble)(void *, uint16_t, uint16_t);
    bool (*rumble_triggers)(void *, uint16_t, uint16_t);
    bool (*set_led)(void *, uint8_t, uint8_t, uint8_t);
    bool (*send_effect)(void *, const void *, int);
    bool (*set_sensors_enabled)(void *, bool);
    void (*cleanup)(void *);
};
_Static_assert(sizeof(struct virtual_desc) == 136, "Unexpected SDL descriptor ABI");
_Static_assert(offsetof(struct virtual_desc, name) == 40, "Unexpected SDL descriptor layout");
struct packet { unsigned char magic[4]; uint32_t version, active, buttons; int16_t axes[6]; uint32_t sequence; };
struct ack { unsigned char magic[4]; uint32_t instance, recognized, sequence; };
_Static_assert(sizeof(struct packet) == 32, "Input packet ABI mismatch");
_Static_assert(sizeof(struct ack) == 16, "Input acknowledgement ABI mismatch");

static char socket_path[sizeof(((struct sockaddr_un *)0)->sun_path)];
static uint32_t instance;
static void *joystick;
static bool recognized;
static uint32_t (*was_init)(uint32_t);
static uint32_t (*attach_virtual)(const struct virtual_desc *);
static void *(*open_joystick)(uint32_t);
static bool (*set_axis)(void *, int, int16_t);
static bool (*set_button)(void *, int, bool);
static bool (*is_gamepad)(uint32_t);
static bool (*detach_virtual)(uint32_t);
static void (*close_joystick)(void *);
static const char *(*get_error)(void);

static bool bind_interfaces(void) {
    if(!rea_sdl3())return false;
#define BIND(variable, type, name) do { variable=(type)rea_sdl_symbol(name);if(!variable)return false; } while(0)
    BIND(was_init,uint32_t (*)(uint32_t),"SDL_WasInit");
    BIND(attach_virtual,uint32_t (*)(const struct virtual_desc *),"SDL_AttachVirtualJoystick");
    BIND(open_joystick,void *(*)(uint32_t),"SDL_OpenJoystick");
    BIND(set_axis,bool (*)(void *,int,int16_t),"SDL_SetJoystickVirtualAxis");
    BIND(set_button,bool (*)(void *,int,bool),"SDL_SetJoystickVirtualButton");
    BIND(is_gamepad,bool (*)(uint32_t),"SDL_IsGamepad");
    BIND(detach_virtual,bool (*)(uint32_t),"SDL_DetachVirtualJoystick");
    BIND(close_joystick,void (*)(void *),"SDL_CloseJoystick");
    BIND(get_error,const char *(*)(void),"SDL_GetError");
#undef BIND
    return true;
}
static void release_device(void) {
    if (joystick && (was_init(0x2000u)&0x2000u)) {
        for (int i=0; i<6; ++i) set_axis(joystick,i,i>=4 ? INT16_MIN : 0);
        for (int i=0; i<15; ++i) set_button(joystick,i,false);
        close_joystick(joystick);
        if (instance) detach_virtual(instance);
    }
    joystick=NULL; instance=0; recognized=false;
}
static bool create_device(void) {
    if (joystick) return true;
    for (int i=0; i<300 && !(was_init(0x2000u)&0x2000u); ++i) usleep(100000);
    if (!(was_init(0x2000u)&0x2000u)) { fprintf(stderr,"REA_PAD SDL gamepad subsystem not initialized\n"); return false; }
    struct virtual_desc desc={0};
    desc.version=sizeof(desc); desc.type=1; desc.naxes=6; desc.nbuttons=15;
    desc.axis_mask=0x3fu; desc.button_mask=0x7fffu; desc.name="Factorio Android Controller Relay";
    instance=attach_virtual(&desc);
    if (!instance) { fprintf(stderr,"REA_PAD attach failed: %s\n",get_error()); return false; }
    joystick=open_joystick(instance);
    if (!joystick) { fprintf(stderr,"REA_PAD open failed: %s\n",get_error()); detach_virtual(instance); instance=0; return false; }
    recognized=is_gamepad(instance);
    fprintf(stderr,"REA_PAD virtual device id=%u recognized_gamepad=%u axes=6 buttons=15\n",instance,(unsigned)recognized);
    return true;
}
static bool read_packet(int fd, struct packet *packet) {
    unsigned char *out=(unsigned char *)packet; size_t remaining=sizeof(*packet);
    while (remaining) {
        ssize_t got=recv(fd,out,remaining,0);
        if (got<0 && errno==EINTR) continue;
        if (got<=0) return false;
        out+=got; remaining-=(size_t)got;
    }
    return !memcmp(packet->magic,"RGP1",4) && packet->version==1 && packet->active<=1 && !(packet->buttons&~0x7fffu);
}
static void serve_peer(int fd) {
    struct ucred peer; socklen_t len=sizeof(peer);
    if (getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&peer,&len) || peer.uid!=getuid()) { fprintf(stderr,"REA_PAD rejected different UID\n"); return; }
    struct packet packet; unsigned packets=0;
    while (read_packet(fd,&packet)) {
        if (!packet.active) release_device();
        else if (create_device()) {
            bool success=true;
            for (int i=0; i<6; ++i) success=set_axis(joystick,i,packet.axes[i]) && success;
            for (int i=0; i<15; ++i) success=set_button(joystick,i,(packet.buttons&(1u<<i))!=0) && success;
            if (!success) { fprintf(stderr,"REA_PAD input update failed: %s\n",get_error()); break; }
        }
        struct ack ack={{'R','G','A','1'},instance,(uint32_t)recognized,packet.sequence};
        if (send(fd,&ack,sizeof(ack),MSG_NOSIGNAL)!=(ssize_t)sizeof(ack)) break;
        ++packets;
    }
    release_device();
    fprintf(stderr,"REA_PAD peer disconnected packets=%u\n",packets);
}
static void *relay_thread(void *ignored) {
    (void)ignored;
    if (!bind_interfaces()) { fprintf(stderr,"REA_PAD required SDL3 interfaces unavailable\n"); return NULL; }
    fprintf(stderr,"REA_PAD SDL3 interfaces resolved from loaded modules\n");
    struct stat existing;
    if (!lstat(socket_path,&existing)) {
        if (!S_ISSOCK(existing.st_mode) || existing.st_uid!=getuid()) { fprintf(stderr,"REA_PAD unexpected existing socket path\n"); return NULL; }
        unlink(socket_path);
    }
    int server=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);
    if (server<0) { fprintf(stderr,"REA_PAD socket failed errno=%d\n",errno); return NULL; }
    struct sockaddr_un address={0}; address.sun_family=AF_UNIX; memcpy(address.sun_path,socket_path,strlen(socket_path)+1);
    if (bind(server,(struct sockaddr *)&address,sizeof(address)) || chmod(socket_path,0600) || listen(server,1)) {
        fprintf(stderr,"REA_PAD private listener failed errno=%d\n",errno); close(server); unlink(socket_path); return NULL;
    }
    fprintf(stderr,"REA_PAD private listener ready\n");
    for (;;) {
        int fd=accept4(server,NULL,NULL,SOCK_CLOEXEC);
        if (fd<0) { if (errno==EINTR) continue; break; }
        serve_peer(fd); close(fd);
    }
    close(server); unlink(socket_path); return NULL;
}
__attribute__((constructor)) static void initialize_relay(void) {
    const char *path=getenv("REA_GAMEPAD_SOCKET");
    if(!path||path[0]!='/'||strlen(path)>=sizeof(socket_path))return;
    char parent[sizeof(socket_path)];strcpy(parent,path);char *slash=strrchr(parent,'/');
    if(!slash||strncmp(slash+1,"pad-",4)||strlen(path)<5||strcmp(path+strlen(path)-5,".sock"))return;
    *slash='\0';struct stat directory;
    if(stat(parent,&directory)||!S_ISDIR(directory.st_mode)||directory.st_uid!=getuid())return;
    memcpy(socket_path,path,strlen(path)+1);
    pthread_t worker;
    int error=pthread_create(&worker,NULL,relay_thread,NULL);
    if (error) fprintf(stderr,"REA_PAD cannot start worker error=%d\n",error);
    else pthread_detach(worker);
}
