/* SPDX-License-Identifier: GPL-3.0-or-later
 * A private xdg-open endpoint for SDL's existing screen-keyboard requests.
 * Built as a static ARM64 executable; does not load or patch the game.
 */
#define _GNU_SOURCE
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/time.h>

int main(int argc, char **argv) {
    const char *name=getenv("REA_IME_SOCKET"), *token=getenv("REA_IME_TOKEN");
    if (argc!=2 || !name || !token || strlen(name)>90 || strlen(token)!=36) return 64;
    const char *url=argv[1];size_t len=strnlen(url,385);
    if (len>384 || (strcmp(url,"steam://close/keyboard") &&
        strncmp(url,"steam://open/keyboard?",22))) return 64;
    for (size_t i=0;i<len;i++) if ((unsigned char)url[i]<32 || (unsigned char)url[i]>126) return 64;
    int fd=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);if(fd<0)return 69;
    struct timeval timeout={2,0};
    setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
    setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
    struct sockaddr_un address={.sun_family=AF_UNIX};
    memcpy(address.sun_path+1,name,strlen(name));
    if(connect(fd,(struct sockaddr*)&address,offsetof(struct sockaddr_un,sun_path)+1+strlen(name))) {close(fd);return 69;}
    struct ucred peer;socklen_t size=sizeof(peer);
    if(getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&peer,&size) || peer.uid!=getuid()) {close(fd);return 77;}
    char line[432];size_t n=0;
    memcpy(line,"RIME1 ",6);n=6;memcpy(line+n,token,36);n+=36;line[n++]=' ';
    memcpy(line+n,url,len);n+=len;line[n++]='\n';
    for(size_t sent=0;sent<n;) {ssize_t s=send(fd,line+sent,n-sent,MSG_NOSIGNAL);if(s<=0){close(fd);return 74;}sent+=(size_t)s;}
    char ack[3];size_t have=0;
    while(have<sizeof(ack)){ssize_t got=read(fd,ack+have,sizeof(ack)-have);if(got<=0){close(fd);return 74;}have+=(size_t)got;}
    close(fd);return memcmp(ack,"OK\n",3)?74:0;
}
