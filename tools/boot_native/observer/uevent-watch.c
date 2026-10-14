/* Standalone AArch64 Linux syscall observer; no libc/NDK, no USB writes.
 * Usage: uevent-watch CASE/NONCE_MARKER NONCE SECONDS EXPECTED_DEVPATH
 * BOOT_NATIVE: SECONDS=0 waits until START under the controller-owned PID/cgroup.
 * Positive 1..60 keeps the original bounded probe semantics. Never changes USB.
 * Receive only kernel-origin netlink uevents. A fresh marker is O_EXCL 0600.
 * stdout records bounded selected fields; USB serial/personal data omitted.
 */
typedef unsigned long usize;
typedef long isize;
typedef unsigned int u32;
struct ts { long sec, ns; };
struct sa { unsigned short family, pad; u32 pid, groups; };
struct io { void *base; usize size; };
struct msg { void *name; u32 namelen; struct io *iov; usize iovlen; void *control; usize controllen; u32 flags; };
struct cm { usize size; int level, type; };
struct cred { int pid; u32 uid, gid; };
struct pfd { int fd; short events, revents; };
#ifdef BOOT_NATIVE_OBSERVER_TEST
static long observer_test_call(long,long,long,long,long,long,long);
#define call observer_test_call
#else
static long call(long n,long a,long b,long c,long d,long e,long f) {
    register long x8 __asm__("x8")=n, x0 __asm__("x0")=a, x1 __asm__("x1")=b,
      x2 __asm__("x2")=c, x3 __asm__("x3")=d, x4 __asm__("x4")=e, x5 __asm__("x5")=f;
    __asm__ volatile("svc #0":"+r"(x0):"r"(x8),"r"(x1),"r"(x2),"r"(x3),"r"(x4),"r"(x5):"memory");
    return x0;
}
#endif
static usize len(const char *s){usize n=0;while(s[n])n++;return n;}
static int eq(const char *a,const char *b){while(*a&&*a==*b){a++;b++;}return *a==*b;}
static void zero(void *p,usize n){char *c=p;while(n--)*c++=0;}
static int put(int fd,const char *s,usize n){while(n){long r=call(64,fd,(long)s,n,0,0,0);if(r==-4)continue;if(r<=0)return -1;s+=r;n-=r;}return 0;}
static char *number(char *p,usize v){char x[32];usize n=0;do{x[n++]='0'+v%10;v/=10;}while(v);while(n)*p++=x[--n];return p;}
static char *copy(char *p,const char *s){while(*s)*p++=*s++;return p;}
static long now_ms(void){struct ts t;if(call(113,1,(long)&t,0,0,0,0)<0)return -1;return t.sec*1000+t.ns/1000000;}
static const char *field(char *buf,usize n,const char *key){usize k=len(key),i=0;while(i<n){usize j=0;while(i+j<n&&buf[i+j])j++;if(i+j>=n)return 0;if(j>k&&buf[i+k]=='='){usize z=0;while(z<k&&buf[i+z]==key[z])z++;if(z==k)return buf+i+k+1;}i+=j+1;}return 0;}
static int digits(const char *s){if(!s||!*s)return 0;while(*s){if(*s<'0'||*s>'9')return 0;s++;}return 1;}
static int parse_seconds(const char *s){
    if(!digits(s)||len(s)>2)return -1;
    int n=0;for(;*s;s++){int d=*s-'0';if(n>(60-d)/10)return -1;n=n*10+d;}return n;
}
static int run(int argc,char **argv){
    if(argc!=5||len(argv[2])!=32||len(argv[1])>230||!digits(argv[3]))return 64;
    for(int i=0;i<32;i++)if(!((argv[2][i]>='0'&&argv[2][i]<='9')||(argv[2][i]>='a'&&argv[2][i]<='f')))return 64;
    if(!eq(argv[4],"/devices/virtual/misc/mtp_usb"))return 64;
    int seconds=parse_seconds(argv[3]);if(seconds<0)return 64;
    long fd=call(198,16,3|02000000|04000,15,0,0,0);if(fd<0)return 65;
    int one=1;if(call(208,fd,1,16,(long)&one,4,0)<0)return 66;
    struct sa local={16,0,0,1};if(call(200,fd,(long)&local,sizeof(local),0,0,0)<0)return 67;
    long start=now_ms();if(start<0)return 68;
    char ready[128],*q=copy(ready,"READY nonce=");q=copy(q,argv[2]);q=copy(q," monotonic_ms=");q=number(q,start);*q++='\n';if(put(1,ready,q-ready))return 69;
    long deadline=seconds ? start+seconds*1000 : 0;int events=0;
    for(;;){
        long tick=now_ms();if(tick<start)return 74;if(deadline&&tick>=deadline)break;
        struct pfd poll={fd,1,0};struct ts wait={0,100000000};long r=call(73,(long)&poll,1,(long)&wait,0,0,0);
        if(r==-4)continue;if(r<0)return 70;if(!r)continue;
        char buf[8192],control[128] __attribute__((aligned(8)));struct sa src;zero(&src,sizeof(src));zero(control,sizeof(control));
        struct io io={buf,sizeof(buf)};struct msg m={&src,sizeof(src),&io,1,control,sizeof(control),0};
        r=call(212,fd,(long)&m,0x40,0,0,0);if(r==-11||r==-4)continue;if(r<=0)return 71;
        if(m.flags&(8|32))return 72; /* MSG_CTRUNC | MSG_TRUNC: fail closed. */
        if(m.namelen!=sizeof(src)||src.family!=16||src.pid!=0)continue;
        int trusted=0;for(usize off=0;off+sizeof(struct cm)<=m.controllen;){struct cm *c=(struct cm *)(control+off);if(c->size<sizeof(*c)||off+c->size>m.controllen)return 73;
            if(c->level==1&&c->type==2&&c->size>=sizeof(*c)+sizeof(struct cred)){struct cred *cr=(struct cred *)(c+1);if(cr->pid==0&&cr->uid==0&&cr->gid==0)trusted=1;}
            off+=(c->size+7)&~7UL;}
        if(!trusted)continue;
        const char *path=field(buf,r,"DEVPATH"),*accessory=field(buf,r,"ACCESSORY"),*seq=field(buf,r,"SEQNUM"),*state=field(buf,r,"USB_STATE");
        if(!path||!eq(path,argv[4]))continue;
        long when=now_ms();if(when<start||(deadline&&when>=deadline))return 74;
        /* Persistent mode stops logging at the budget, never stops matching START. */
        if(seconds||events<128){
        char line[512],*p=copy(line,"KERNEL sender_portid=0 uid=0 monotonic_ms=");p=number(p,when);p=copy(p," DEVPATH=");p=copy(p,path);
        if(accessory&&len(accessory)<32){p=copy(p," ACCESSORY=");p=copy(p,accessory);}if(state&&len(state)<32){p=copy(p," USB_STATE=");p=copy(p,state);}if(seq&&digits(seq)&&len(seq)<24){p=copy(p," SEQNUM=");p=copy(p,seq);}*p++='\n';if(put(1,line,p-line))return 75;
        if(++events>128)return 76;
        }
        if(accessory&&eq(accessory,"START")&&seq&&digits(seq)&&len(seq)<24){
            char out[512],*z=copy(out,argv[2]);*z++=' ';z=number(z,when);*z++=' ';z=copy(z,seq);z=copy(z," 0 0 ");z=copy(z,path);*z++='\n';
            long marker=call(56,-100,(long)argv[1],01|0100|0200|0400000|02000000,0600,0,0); /* WRONLY CREAT EXCL NOFOLLOW CLOEXEC */
            if(marker<0)return 77;if(put(marker,out,z-out)){call(57,marker,0,0,0,0,0);return 78;}
            if(call(82,marker,0,0,0,0,0)<0)return 79; /* fsync */
            if(call(57,marker,0,0,0,0,0)<0)return 80;call(57,fd,0,0,0,0,0);return 0;
        }
    }
    call(57,fd,0,0,0,0,0);return 2;
}
#ifndef BOOT_NATIVE_OBSERVER_TEST
__attribute__((used)) static void entry(long *stack){int code=run((int)stack[0],(char **)(stack+1));call(93,code,0,0,0,0,0);__builtin_unreachable();}
__attribute__((naked,noreturn)) void _start(void){__asm__ volatile("mov x0, sp\nbl entry\n");}

#endif
