/* Read-only SDL platform flags. No ptrace, signals, writes or typed text. */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
static int peek(int fd,uint64_t address,void *dest,size_t n){return pread(fd,dest,n,(off_t)address)==(ssize_t)n;}
int main(int argc,char **argv){
    if(argc!=2)return 64;
    char *end;long pid=strtol(argv[1],&end,10);if(*end||pid<1)return 64;
    char path[80],line[1024];snprintf(path,sizeof(path),"/proc/%ld/maps",pid);
    FILE *maps=fopen(path,"r");if(!maps){printf("SDL_STATE maps_errno=%d\n",errno);return 1;}
    unsigned long long base=0,start,offset;
    while(fgets(line,sizeof(line),maps)){
        if(strstr(line,"/factorio/bin/arm64/factorio")&&sscanf(line,"%llx-%*x %*s %llx",&start,&offset)==2&&offset==0){base=start;break;}
    }
    fclose(maps);if(!base){puts("SDL_STATE engine_mapping_missing=true");return 2;}
    snprintf(path,sizeof(path),"/proc/%ld/mem",pid);int fd=open(path,O_RDONLY|O_CLOEXEC);
    if(fd<0){printf("SDL_STATE mem_errno=%d\n",errno);return 3;}
    uint64_t device=0,internal=0,show_callback=0;unsigned char enabled=0,shown=0;
    if(!peek(fd,base+0x40e4b10,&device,8)||!device||!peek(fd,device+0x680,&internal,8)||!internal||
        !peek(fd,internal+0x728,&enabled,1)||!peek(fd,device+0x39c,&shown,1)||!peek(fd,device+0x288,&show_callback,8)){
        printf("SDL_STATE read_errno=%d\n",errno);close(fd);return 4;
    }
    close(fd);printf("SDL_STATE steam_keyboard_enabled=%u keyboard_shown=%u expected_show_callback=%d read_only=true\n",enabled,shown,show_callback==base+0x37cd9e0);return 0;
}
