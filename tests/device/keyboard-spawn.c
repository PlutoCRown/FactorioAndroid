/* Diagnostic only: exercise SDL's GNU libc process egress, without a game. */
#define _GNU_SOURCE
#include <spawn.h>
#include <sys/wait.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
extern char **environ;
int main(void) {
    char *args[]={"xdg-open","steam://close/keyboard",NULL};
    pid_t child;int status=-1;
    /* No bridge credentials: successful execution of our helper must exit 64. */
    unsetenv("REA_IME_TOKEN");unsetenv("REA_IME_SOCKET");
    int result=posix_spawnp(&child,args[0],NULL,NULL,args,environ);
    if(result){printf("GNU_SPAWN direct_error=%d errno=%d\n",result,errno);return 1;}
    if(waitpid(child,&status,0)!=child||!WIFEXITED(status)||WEXITSTATUS(status)!=64){printf("GNU_SPAWN direct_status=%d\n",status);return 2;}
    child=vfork();
    if(child<0){printf("GNU_SPAWN vfork_error=%d\n",errno);return 3;}
    if(!child){pid_t detached;setsid();result=posix_spawnp(&detached,args[0],NULL,NULL,args,environ);_exit(result?result:0);}
    if(waitpid(child,&status,0)!=child||status){printf("GNU_SPAWN background_status=%d\n",status);return 4;}
    puts("GNU_SPAWN direct_helper_exit=64 background_spawn=true passed=true");return 0;
}
