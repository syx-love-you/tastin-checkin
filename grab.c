// 塔斯汀 token 提取器 —— 零依赖原生版本
// 直接调用 Win32 API 读取微信小程序进程内存
// 不需要 Python / frida / Node.js，双击即可运行
//
// 编译： gcc -O2 -s -o 提取Token.exe grab.c

#include <windows.h>
#include <tlhelp32.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "qr_template.h"

#define MAX_TOKENS 16
#define TOKEN_MAX  96

static int is_tok_char(unsigned char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || c == '-';
}

/* token 形如 sssXXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX */
static int looks_like_token(const char *s, int len) {
    if (len < 20 || len > 72) return 0;
    if (strncmp(s, "sss", 3) != 0) return 0;
    int dashes = 0, digits = 0;
    for (int i = 0; i < len; i++) {
        unsigned char c = (unsigned char)s[i];
        if (!is_tok_char(c)) return 0;
        if (c == '-') dashes++;
        else if (c >= '0' && c <= '9') digits++;
    }
    return dashes >= 3 && digits >= 8;
}

/* 列出所有 WeChatAppEx.exe。不猜哪个是主进程，全扫一遍更可靠 */
static int list_appex_pids(DWORD *out, int max) {
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return 0;

    int n = 0;
    PROCESSENTRY32W pe;
    pe.dwSize = sizeof(pe);
    if (Process32FirstW(snap, &pe)) {
        do {
            if (_wcsicmp(pe.szExeFile, L"WeChatAppEx.exe") != 0) continue;
            if (n < max) out[n++] = pe.th32ProcessID;
        } while (Process32NextW(snap, &pe));
    }
    CloseHandle(snap);
    return n;
}

/* 扫描一个进程，返回找到的 token 数；-1 表示打开失败 */
static int scan_process(DWORD pid, char out[MAX_TOKENS][TOKEN_MAX], int *err_out) {
    HANDLE h = OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, FALSE, pid);
    if (!h) { if (err_out) *err_out = (int)GetLastError(); return -1; }

    int count = 0;
    MEMORY_BASIC_INFORMATION mbi;
    unsigned char *buf = (unsigned char *)malloc(1 << 20);
    if (!buf) { CloseHandle(h); return 0; }

    unsigned long long addr = 0;
    const unsigned long long limit = 0x7FFFFFFFFFFFULL;

    while (addr < limit) {
        if (VirtualQueryEx(h, (LPCVOID)(uintptr_t)addr, &mbi, sizeof(mbi)) == 0) break;

        DWORD prot = mbi.Protect;
        int readable = mbi.State == MEM_COMMIT
            && !(prot & PAGE_GUARD) && !(prot & PAGE_NOACCESS)
            && (prot == PAGE_READONLY || prot == PAGE_READWRITE
                || prot == PAGE_WRITECOPY || prot == PAGE_EXECUTE_READ
                || prot == PAGE_EXECUTE_READWRITE || prot == PAGE_EXECUTE_WRITECOPY);

        if (readable && mbi.RegionSize > 0 && mbi.RegionSize <= (256u << 20)) {
            SIZE_T off = 0;
            while (off < mbi.RegionSize) {
                SIZE_T chunk = (1 << 20);
                if (chunk > mbi.RegionSize - off) chunk = mbi.RegionSize - off;

                SIZE_T got = 0;
                if (ReadProcessMemory(h, (LPCVOID)((uintptr_t)mbi.BaseAddress + off),
                                      buf, chunk, &got) && got > 0) {
                    for (SIZE_T i = 0; i + 3 < got; i++) {
                        if (buf[i] != 's' || buf[i + 1] != 's' || buf[i + 2] != 's') continue;
                        SIZE_T j = i;
                        while (j < got && j - i < TOKEN_MAX - 1 && is_tok_char(buf[j])) j++;
                        int len = (int)(j - i);
                        if (len < 20) continue;

                        char tmp[TOKEN_MAX];
                        memcpy(tmp, buf + i, len);
                        tmp[len] = 0;
                        while (len > 0 && tmp[len - 1] == '-') tmp[--len] = 0;

                        if (!looks_like_token(tmp, len)) continue;

                        int dup = 0;
                        for (int k = 0; k < count; k++)
                            if (strcmp(out[k], tmp) == 0) { dup = 1; break; }
                        if (!dup && count < MAX_TOKENS) strcpy(out[count++], tmp);
                    }
                }
                off += chunk;
            }
        }

        if (mbi.RegionSize == 0) break;
        addr = (unsigned long long)(uintptr_t)mbi.BaseAddress + mbi.RegionSize;
    }

    free(buf);
    CloseHandle(h);
    return count;
}


/* 把 token 填进内嵌模板，生成 html 并用默认浏览器打开 */
static void show_qr(const char *token) {
    /* 用宽字符 API，避免中文文件名在 fopen 下失败 */
    wchar_t wpath[MAX_PATH];
    GetCurrentDirectoryW(MAX_PATH, wpath);
    wcscat(wpath, L"\\二维码.html");

    FILE *f = _wfopen(wpath, L"wb");
    if (!f) {
        printf("  (无法写入二维码文件，请手动复制 token)\n");
        return;
    }

    /* 模板里 @@TOKEN@@ 出现两次，逐段写出并替换 */
    const char *p = (const char *)QR_TEMPLATE;
    const char *mark = "@@TOKEN@@";
    size_t mlen = strlen(mark);
    while (*p) {
        const char *hit = strstr(p, mark);
        if (!hit) { fputs(p, f); break; }
        fwrite(p, 1, (size_t)(hit - p), f);
        fputs(token, f);
        p = hit + mlen;
    }
    fclose(f);

    printf("  已生成二维码页面，正在打开...\n");
    ShellExecuteW(NULL, L"open", wpath, NULL, NULL, SW_SHOWNORMAL);
}

/* 扫一遍所有小程序进程，返回去重后的 token 数；*denied 记录打不开的进程数 */
static int collect_tokens(const DWORD *pids, int np,
                          char tokens[MAX_TOKENS][TOKEN_MAX], int *denied) {
    int n = 0;
    *denied = 0;
    for (int i = 0; i < np; i++) {
        char tmp[MAX_TOKENS][TOKEN_MAX];
        int err = 0;
        int got = scan_process(pids[i], tmp, &err);
        if (got < 0) { if (err == 5) (*denied)++; continue; }
        for (int k = 0; k < got; k++) {
            int dup = 0;
            for (int m = 0; m < n; m++) if (strcmp(tokens[m], tmp[k]) == 0) { dup = 1; break; }
            if (!dup && n < MAX_TOKENS) strcpy(tokens[n++], tmp[k]);
        }
    }
    return n;
}

int main(void) {
    SetConsoleOutputCP(65001);

    printf("==================================================\n");
    printf("  塔斯汀 token 提取  v1.0\n");
    printf("==================================================\n\n");

    printf("[1/3] 检测小程序...\n\n");

    DWORD pids[64];
    int np = 0;

    /* 先等用户打开小程序。微信开着但没进小程序时，不会有 WeChatAppEx 进程 */
    for (int wait = 0; wait < 60; wait++) {
        np = list_appex_pids(pids, 64);
        if (np > 0) break;
        if (wait == 0) {
            printf("  请先在微信里打开「塔斯汀」小程序。\n");
            printf("  打开后会自动继续，不用在这里操作。\n\n");
        }
        if (wait == 10 || wait == 30) printf("  还在等待…\n");
        Sleep(2000);
    }

    if (np == 0) {
        printf("\n  等了 2 分钟，没检测到微信小程序。\n\n");
        printf("  请确认：\n");
        printf("    1. 电脑上的微信已登录\n");
        printf("    2. 已经点开「塔斯汀」小程序，并停留在小程序页面\n");
        printf("    3. 不是只有微信聊天窗口\n");
        printf("\n按回车退出...\n");
        getchar();
        return 1;
    }

    printf("      检测到 %d 个小程序进程\n", np);
    printf("      等它加载完整…\n");
    Sleep(2500);

    static char tokens[MAX_TOKENS][TOKEN_MAX];
    int n = 0, denied = 0;

    /* 最多试 3 轮：常见情况是小程序刚开还没加载完 */
    for (int attempt = 1; attempt <= 3; attempt++) {
        printf("\n[2/3] 读取内存（第 %d 次）...\n", attempt);

        np = list_appex_pids(pids, 64);
        n = collect_tokens(pids, np, tokens, &denied);

        if (n > 0) break;

        if (attempt < 3) {
            printf("\n      没读到 token。\n");
            printf("      请确认小程序页面已经打开、内容加载出来了。\n");
            printf("      5 秒后自动重试…\n");
            Sleep(5000);
        }
    }

    if (n == 0) {
        printf("\n[3/3] 仍然没拿到 token\n\n");
        if (denied > 0) {
            printf("  程序没有权限读取微信的内存。\n\n");
            printf("  解决办法：右键本程序 → 「以管理员身份运行」。\n");
        } else {
            printf("  能读到微信内存，但里面没有 token。\n\n");
            printf("  可能原因：\n");
            printf("    - 打开的还不是「塔斯汀」小程序\n");
            printf("    - 小程序停在首页，还没加载出数据\n");
            printf("    - 微信没登录\n");
            printf("\n  把小程序切到首页/签到页，再运行一次。\n");
        }
        printf("\n按回车退出...\n");
        getchar();
        return 1;
    }

    printf("\n[3/3] 找到 %d 个 token\n\n", n);
    printf("==================================================\n");
    for (int i = 0; i < n; i++) printf("TOKEN=%s\n", tokens[i]);
    printf("==================================================\n");

    FILE *f = fopen("config.json", "w");
    if (f) {
        fprintf(f, "{\n  \"token\": \"%s\",\n  \"phone\": \"\"\n}\n", tokens[0]);
        fclose(f);
        printf("\n已保存到 config.json\n");
    }

    show_qr(tokens[0]);
    printf("\n如果浏览器没自动打开，双击同目录下的「二维码.html」。\n");
    printf("也可以复制 TOKEN= 后面那一串，手动粘贴到 App 里。\n");
    printf("\n按回车退出...\n");
    getchar();
    return 0;
}
