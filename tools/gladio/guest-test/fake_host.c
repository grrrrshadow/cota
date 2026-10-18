/*
 * Minimal stand-in for the Winlator X server + gladiorenderer, enough to drive the guest libGL
 * (running under qemu-aarch64) through the real Gladio protocol: X11 setup, the GLX extension
 * opcodes the guest uses, the ashmem rings (memfd here) and a handful of GL requests. Every GL
 * request is logged to stdout, guest_test.c and run.sh check the log.
 *
 *   fake_host SOCKET_PATH
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <stdint.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>

#include "ring_buffer.h"
#include "socket_utils.h"
#include "request_codes.h"

#define SERVER_RING_BUFFER_SIZE 67108864
#define CLIENT_RING_BUFFER_SIZE 33554432
#define HEADER_SIZE 6

#define GL_PIXEL_PACK_BUFFER 0x88EB
#define GL_PIXEL_UNPACK_BUFFER 0x88EC

static int clientFd;
static pthread_mutex_t logMutex = PTHREAD_MUTEX_INITIALIZER;
static bool pixelPackBufferBound = false;
static bool pixelUnpackBufferBound = false;
// Memory of the last pack buffer the guest allocated, shared with the guest like the real host's
static unsigned char* pixelPackBufferData = NULL;
static int pixelPackBufferSize = 0;

#define LOG(...) do { pthread_mutex_lock(&logMutex); printf(__VA_ARGS__); fflush(stdout); pthread_mutex_unlock(&logMutex); } while (0)

static void readFully(int fd, void* data, int size) {
    if (sock_read(fd, data, size) != size) {
        LOG("x11 connection closed\n");
        exit(0);
    }
}

static void sendReply(int fd, int length) {
    char reply[32] = {1};
    *(int*)(reply + 4) = length;
    write(fd, reply, sizeof(reply));
}

static int createSharedMemory(const char* name, int size) {
    int fd = memfd_create(name, MFD_CLOEXEC);
    if (fd < 0 || ftruncate(fd, size) < 0) {
        perror("memfd");
        exit(1);
    }
    return fd;
}

static bool readRequest(RingBuffer* ring, short* requestCode, char** data, int* size) {
    char header[HEADER_SIZE];
    if (!RingBuffer_read(ring, header, HEADER_SIZE)) return false;
    *requestCode = *(short*)header;
    *size = *(int*)(header + 2);
    *data = realloc(*data, *size > 0 ? *size : 1);
    return *size == 0 || RingBuffer_read(ring, *data, *size);
}

static void reply(RingBuffer* ring, short requestCode, void* data, int size) {
    char header[HEADER_SIZE];
    *(short*)header = requestCode;
    *(int*)(header + 2) = size;
    RingBuffer_write(ring, header, HEADER_SIZE);
    if (size > 0) RingBuffer_write(ring, data, size);
}

static void* contextThread(void* param) {
    RingBuffer** rings = param;
    RingBuffer* serverRing = rings[0];
    RingBuffer* clientRing = rings[1];
    char* data = NULL;
    short requestCode;
    int size;

    while (readRequest(serverRing, &requestCode, &data, &size)) {
        int* args = (int*)data;
        switch (requestCode) {
            case REQUEST_CODE_SET_CURRENT_RENDER_WINDOW:
                LOG("SET_CURRENT_RENDER_WINDOW window=%d context=%d\n", args[0], args[1]);
                reply(clientRing, requestCode, NULL, 0);
                break;
            case REQUEST_CODE_SWAP_DISPLAY_BUFFERS:
                LOG("SWAP_DISPLAY_BUFFERS\n");
                reply(clientRing, requestCode, NULL, 0);
                break;
            case REQUEST_CODE_GL_BIND_BUFFER:
                LOG("BIND_BUFFER target=0x%x id=%d\n", args[0], args[1]);
                if (args[0] == GL_PIXEL_PACK_BUFFER) pixelPackBufferBound = args[1] != 0;
                if (args[0] == GL_PIXEL_UNPACK_BUFFER) pixelUnpackBufferBound = args[1] != 0;
                break;
            case REQUEST_CODE_GL_PIXEL_STOREI:
                LOG("PIXEL_STOREI pname=0x%x param=%d\n", args[0], args[1]);
                break;
            case REQUEST_CODE_GL_BUFFER_STORAGE: {
                LOG("BUFFER_STORAGE target=0x%x size=%d flags=0x%x\n", args[0], args[1], args[2]);
                int fd = createSharedMemory("gl-buffer", args[1]);
                if (args[0] == GL_PIXEL_PACK_BUFFER) {
                    pixelPackBufferData = mmap(NULL, args[1], PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
                    pixelPackBufferSize = args[1];
                }
                bool success = true;
                send_fds(clientFd, &fd, 1, &success, sizeof(bool));
                close(fd);
                break;
            }
            case REQUEST_CODE_GL_BUFFER_DATA:
                LOG("BUFFER_DATA target=0x%x size=%d\n", args[0], args[1]);
                reply(clientRing, requestCode, NULL, 0);
                break;
            case REQUEST_CODE_GL_BUFFER_SUB_DATA:
                LOG("BUFFER_SUB_DATA target=0x%x offset=%d size=%d\n", args[0], args[1], args[2]);
                reply(clientRing, requestCode, NULL, 0);
                break;
            case REQUEST_CODE_GL_FLUSH_MAPPED_BUFFER_RANGE:
                LOG("FLUSH_MAPPED_BUFFER_RANGE target=0x%x offset=%d length=%d\n", args[0], args[1], args[2]);
                reply(clientRing, requestCode, NULL, 0);
                break;
            case REQUEST_CODE_GL_DRAW_ELEMENTS:
                LOG("DRAW_ELEMENTS mode=%d count=%d type=0x%x\n", args[0], args[1], args[2]);
                break;
            case REQUEST_CODE_GL_TEX_SUB_IMAGE2D: {
                // target level x y width height format type imageSize [pointer]
                int imageSize = args[8];
                if (pixelUnpackBufferBound) {
                    LOG("TEX_SUB_IMAGE2D %dx%d format=0x%x type=0x%x offset=%d\n", args[4], args[5], args[6], args[7], args[9]);
                    reply(clientRing, requestCode, NULL, 0);
                    break;
                }
                unsigned char* pixels = malloc(imageSize > 0 ? imageSize : 1);
                if (imageSize > 0) RingBuffer_read(serverRing, pixels, imageSize);
                LOG("TEX_SUB_IMAGE2D %dx%d format=0x%x type=0x%x size=%d data=", args[4], args[5], args[6], args[7], imageSize);
                for (int i = 0; i < imageSize; i++) LOG("%02x", pixels[i]);
                LOG("\n");
                free(pixels);
                break;
            }
            case REQUEST_CODE_GL_READ_PIXELS: {
                // x y width height format type [pointer]: answer with tightly packed bytes 1, 2, 3...
                if (pixelPackBufferBound) {
                    // Answer late with bytes 0x51, 0x52..., a guest that does not wait reads them too early
                    LOG("READ_PIXELS %dx%d into pack buffer offset=%d\n", args[2], args[3], args[6]);
                    usleep(300000);
                    int imageSize = args[2] * args[3] * 4;
                    if (pixelPackBufferData && args[6] + imageSize <= pixelPackBufferSize) {
                        for (int i = 0; i < imageSize; i++) pixelPackBufferData[args[6] + i] = 0x51 + i;
                    }
                    reply(clientRing, requestCode, NULL, 0);
                    break;
                }
                int bytesPerPixel = args[4] == 0x1907 ? 3 : 4;
                int imageSize = args[2] * args[3] * bytesPerPixel;
                LOG("READ_PIXELS %dx%d format=0x%x size=%d\n", args[2], args[3], args[4], imageSize);
                unsigned char* pixels = malloc(imageSize);
                for (int i = 0; i < imageSize; i++) pixels[i] = i + 1;
                reply(clientRing, requestCode, &imageSize, sizeof(int));
                RingBuffer_write(clientRing, pixels, imageSize);
                free(pixels);
                break;
            }
            case REQUEST_CODE_GL_GET_TEX_IMAGE: {
                int imageSize = 8;
                unsigned char pixels[8] = {0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8};
                LOG("GET_TEX_IMAGE size=%d\n", imageSize);
                reply(clientRing, requestCode, &imageSize, sizeof(int));
                RingBuffer_write(clientRing, pixels, imageSize);
                break;
            }
            default:
                LOG("REQUEST %d size=%d\n", requestCode, size);
                break;
        }
    }
    return NULL;
}

int main(int argc, char** argv) {
    int serverSocket = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    strncpy(address.sun_path, argv[1], sizeof(address.sun_path) - 1);
    unlink(argv[1]);
    if (bind(serverSocket, (struct sockaddr*)&address, sizeof(address)) < 0 || listen(serverSocket, 1) < 0) {
        perror("bind");
        return 1;
    }
    LOG("listening\n");

    clientFd = accept(serverSocket, NULL, NULL);
    char setup[12];
    readFully(clientFd, setup, sizeof(setup));
    char setupReply[8] = {1};
    write(clientFd, setupReply, sizeof(setupReply));

    while (true) {
        unsigned char header[4];
        readFully(clientFd, header, sizeof(header));
        char payload[4096];

        switch (header[1]) {
            case 1: { // CREATE_GL_CONTEXT: thread id, answered with the two ring fds
                readFully(clientFd, payload, 4);
                int fds[2] = {
                    createSharedMemory("gl-server-ring", RingBuffer_getSHMemSize(SERVER_RING_BUFFER_SIZE)),
                    createSharedMemory("gl-client-ring", RingBuffer_getSHMemSize(CLIENT_RING_BUFFER_SIZE))
                };
                RingBuffer** rings = calloc(2, sizeof(RingBuffer*));
                rings[0] = RingBuffer_create(fds[0], SERVER_RING_BUFFER_SIZE);
                rings[1] = RingBuffer_create(fds[1], CLIENT_RING_BUFFER_SIZE);
                send_fds(clientFd, fds, 2, NULL, 0);
                close(fds[0]);
                close(fds[1]);
                pthread_t thread;
                pthread_create(&thread, NULL, contextThread, rings);
                LOG("CREATE_GL_CONTEXT\n");
                break;
            }
            case 2: // DESTROY_GL_CONTEXT
                readFully(clientFd, payload, 4);
                sendReply(clientFd, 0);
                LOG("DESTROY_GL_CONTEXT\n");
                break;
            case 3: // CREATE_CONTEXT: context, 0, 0, share list, direct
                readFully(clientFd, payload, 17);
                sendReply(clientFd, 0);
                LOG("CREATE_CONTEXT id=%d\n", *(int*)payload);
                break;
            case 4: // DESTROY_CONTEXT
                readFully(clientFd, payload, 4);
                LOG("DESTROY_CONTEXT\n");
                break;
            case 34: { // CREATE_CONTEXT_ATTRIBS_ARB: context, fbconfig, 0, share, direct+pad, count, pairs
                readFully(clientFd, payload, 24);
                int numAttribs = *(int*)(payload + 20);
                readFully(clientFd, payload + 24, numAttribs * 8);
                sendReply(clientFd, 0);
                LOG("CREATE_CONTEXT_ATTRIBS_ARB id=%d attribs=%d\n", *(int*)payload, numAttribs);
                break;
            }
            default:
                LOG("unexpected GLX opcode %d\n", header[1]);
                return 1;
        }
    }
}
