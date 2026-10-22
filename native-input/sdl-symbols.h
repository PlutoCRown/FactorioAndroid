/* SPDX-License-Identifier: GPL-3.0-or-later
 * Resolve the loaded engine/SDL interfaces by symbol, including static SDL in
 * an executable's ELF symbol table. No game digest, offsets or instruction bytes.
 */
#pragma once
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

struct rea_symbols {const unsigned char *data;size_t size;uintptr_t base;};
static inline bool rea_range(size_t size,uint64_t offset,uint64_t length) {
    return offset<=size&&length<=size-offset;
}
static inline bool rea_symbols_open(struct rea_symbols *s,const char *path,uintptr_t base) {
    memset(s,0,sizeof(*s));int fd=open(path,O_RDONLY|O_CLOEXEC);struct stat st;
    if(fd<0)return false;
    if(fstat(fd,&st)||st.st_size<(off_t)sizeof(Elf64_Ehdr)||(uint64_t)st.st_size>SIZE_MAX){close(fd);return false;}
    void *data=mmap(NULL,(size_t)st.st_size,PROT_READ,MAP_PRIVATE,fd,0);close(fd);
    if(data==MAP_FAILED)return false;
    s->data=data;s->size=(size_t)st.st_size;s->base=base;
    const Elf64_Ehdr *h=data;
    if(memcmp(h->e_ident,ELFMAG,SELFMAG)||h->e_ident[EI_CLASS]!=ELFCLASS64||h->e_ident[EI_DATA]!=ELFDATA2LSB||
       h->e_shentsize!=sizeof(Elf64_Shdr)||!h->e_shnum||
       !rea_range(s->size,h->e_shoff,(uint64_t)h->e_shnum*sizeof(Elf64_Shdr))) {
        munmap(data,s->size);memset(s,0,sizeof(*s));return false;
    }
    return true;
}
static inline void rea_symbols_close(struct rea_symbols *s) {
    if(s->data)munmap((void *)s->data,s->size);memset(s,0,sizeof(*s));
}
static inline bool rea_function_address(const struct rea_symbols *s,uint64_t value) {
    const Elf64_Ehdr *h=(const Elf64_Ehdr *)s->data;
    if(h->e_phentsize!=sizeof(Elf64_Phdr)||!rea_range(s->size,h->e_phoff,(uint64_t)h->e_phnum*sizeof(Elf64_Phdr)))return false;
    for(unsigned i=0;i<h->e_phnum;i++) {
        Elf64_Phdr p;memcpy(&p,s->data+h->e_phoff+(uint64_t)i*sizeof(p),sizeof(p));
        if(p.p_type==PT_LOAD&&(p.p_flags&PF_X)&&value>=p.p_vaddr&&value-p.p_vaddr<p.p_memsz)return true;
    }
    return false;
}
static inline void *rea_symbols_find(const struct rea_symbols *s,const char *name) {
    if(!s->data)return NULL;
    const Elf64_Ehdr *h=(const Elf64_Ehdr *)s->data;
    for(unsigned i=0;i<h->e_shnum;i++) {
        Elf64_Shdr tab;memcpy(&tab,s->data+h->e_shoff+(uint64_t)i*sizeof(tab),sizeof(tab));
        if((tab.sh_type!=SHT_SYMTAB&&tab.sh_type!=SHT_DYNSYM)||tab.sh_entsize!=sizeof(Elf64_Sym)||
           tab.sh_link>=h->e_shnum||!rea_range(s->size,tab.sh_offset,tab.sh_size))continue;
        Elf64_Shdr strings;memcpy(&strings,s->data+h->e_shoff+(uint64_t)tab.sh_link*sizeof(strings),sizeof(strings));
        if(strings.sh_type!=SHT_STRTAB||!rea_range(s->size,strings.sh_offset,strings.sh_size))continue;
        for(uint64_t j=0;j<tab.sh_size/sizeof(Elf64_Sym);j++) {
            Elf64_Sym sym;memcpy(&sym,s->data+tab.sh_offset+j*sizeof(sym),sizeof(sym));
            if(sym.st_shndx==SHN_UNDEF||ELF64_ST_TYPE(sym.st_info)!=STT_FUNC||sym.st_name>=strings.sh_size)continue;
            const char *n=(const char *)s->data+strings.sh_offset+sym.st_name;
            size_t available=(size_t)(strings.sh_size-sym.st_name),length=strnlen(n,available);
            if(length==available)continue;
            bool match=length==strlen(name)&&!strcmp(n,name);
            if(!match&&length==strlen(name)+5)match=!strncmp(n,name,strlen(name))&&!strcmp(n+strlen(name),"_REAL");
            if(!match||!rea_function_address(s,sym.st_value)||sym.st_value>UINTPTR_MAX-s->base)continue;
            return (void *)(s->base+(uintptr_t)sym.st_value);
        }
    }
    return NULL;
}
static inline void **rea_video_pointer(const struct rea_symbols *s) {
    if(!s->data||!rea_symbols_find(s,"SDL_ScreenKeyboardShown"))return NULL;
    const Elf64_Ehdr *h=(const Elf64_Ehdr *)s->data;uintptr_t result=0;
    for(unsigned i=0;i<h->e_shnum;i++) {
        Elf64_Shdr tab;memcpy(&tab,s->data+h->e_shoff+(uint64_t)i*sizeof(tab),sizeof(tab));
        if(tab.sh_type!=SHT_SYMTAB||tab.sh_entsize!=sizeof(Elf64_Sym)||tab.sh_link>=h->e_shnum||!rea_range(s->size,tab.sh_offset,tab.sh_size))continue;
        Elf64_Shdr strings;memcpy(&strings,s->data+h->e_shoff+(uint64_t)tab.sh_link*sizeof(strings),sizeof(strings));
        if(!rea_range(s->size,strings.sh_offset,strings.sh_size))continue;
        for(uint64_t j=0;j<tab.sh_size/sizeof(Elf64_Sym);j++) {
            Elf64_Sym sym;memcpy(&sym,s->data+tab.sh_offset+j*sizeof(sym),sizeof(sym));
            if(!sym.st_shndx||ELF64_ST_TYPE(sym.st_info)!=STT_OBJECT||sym.st_size!=sizeof(void *)||sym.st_name>=strings.sh_size)continue;
            const char *name=(const char *)s->data+strings.sh_offset+sym.st_name;
            if(strnlen(name,(size_t)(strings.sh_size-sym.st_name))!=5||memcmp(name,"_this",5))continue;
            if(sym.st_value>UINTPTR_MAX-s->base)return NULL;
            uintptr_t address=s->base+(uintptr_t)sym.st_value;
            if(result&&result!=address)return NULL;
            // The symbol must occupy readable mapped data, not a guessed structure offset.
            if(h->e_phentsize!=sizeof(Elf64_Phdr)||!rea_range(s->size,h->e_phoff,(uint64_t)h->e_phnum*sizeof(Elf64_Phdr)))return NULL;
            for(unsigned k=0;k<h->e_phnum;k++) {
                Elf64_Phdr p;memcpy(&p,s->data+h->e_phoff+(uint64_t)k*sizeof(p),sizeof(p));
                if(p.p_type==PT_LOAD&&(p.p_flags&PF_R)&&sym.st_value>=p.p_vaddr&&p.p_memsz>=sizeof(void *)&&sym.st_value-p.p_vaddr<=p.p_memsz-sizeof(void *))result=address;
            }
        }
    }
    return (void **)result;
}
static inline int rea_find_video(struct dl_phdr_info *info,size_t size,void *userdata) {
    (void)size;void ***result=userdata;struct rea_symbols symbols;
    const char *path=info->dlpi_name&&*info->dlpi_name?info->dlpi_name:getenv("REA_ENGINE_PATH");
    if(!path||!*path)path="/proc/self/exe";
    if(rea_symbols_open(&symbols,path,info->dlpi_addr)) {*result=rea_video_pointer(&symbols);rea_symbols_close(&symbols);}
    return *result!=NULL;
}
static inline void **rea_sdl_video_pointer(void) {void **result=NULL;dl_iterate_phdr(rea_find_video,&result);return result;}
struct rea_query {const char *name;void *value;};
static inline int rea_find_module(struct dl_phdr_info *info,size_t size,void *userdata) {
    (void)size;struct rea_query *q=userdata;struct rea_symbols symbols;
    const char *path=info->dlpi_name&&*info->dlpi_name?info->dlpi_name:getenv("REA_ENGINE_PATH");
    if(!path||!*path)path="/proc/self/exe";
    if(rea_symbols_open(&symbols,path,info->dlpi_addr)) {
        q->value=rea_symbols_find(&symbols,q->name);rea_symbols_close(&symbols);
    }
    return q->value!=NULL;
}
static inline void *rea_sdl_symbol(const char *name) {
    void *p=dlsym(RTLD_DEFAULT,name);if(p)return p;
    struct rea_query q={name,NULL};dl_iterate_phdr(rea_find_module,&q);
    if(!q.value)fprintf(stderr,"REA_SDL missing_interface=%s\n",name);
    return q.value;
}
static inline bool rea_sdl3(void) {
    int (*version)(void)=(int (*)(void))rea_sdl_symbol("SDL_GetVersion");
    return version&&version()/1000000==3;
}
