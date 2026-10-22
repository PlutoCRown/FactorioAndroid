/* Regression: LTO may rename the active video global while retaining an
 * unrelated _this. Run in its own GNU process; never loads the game. */
#define _GNU_SOURCE
#include "../../native-input/sdl-symbols.h"
static const char driver[]="x11",other[]="input";
static struct {const char *name;} video={driver},input={other},second={driver};
void *input_slot __asm__("_this")=&input;
void *video_slot __asm__("_this.llvm.123456")=&video;
void *second_slot __asm__("_this.987654")=NULL;
bool SDL_ScreenKeyboardShown(void *window) {return window!=NULL;}
int main(int argc,char **argv) {
    (void)argc;setenv("REA_ENGINE_PATH",argv[0],1);
    if(rea_sdl_video_device(driver)!=&video)return 1;
    video_slot=NULL;if(rea_sdl_video_device(driver)!=NULL)return 2;
    video_slot=(void *)1;if(rea_sdl_video_device(driver)!=NULL)return 3;
    video_slot=&video;second_slot=&second;
    if(rea_sdl_video_device(driver)!=NULL)return 4;
    second_slot=&video;if(rea_sdl_video_device(driver)!=&video)return 5;
    puts("SDL_VIDEO_DEVICE checks=5 passed=true");return 0;
}
