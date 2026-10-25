package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL11C.GL_RENDERER;
import static org.lwjgl.opengl.GL11C.GL_VENDOR;
import static org.lwjgl.opengl.GL11C.GL_VERSION;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glGetString;
import static org.lwjgl.opengl.GL32C.GL_CONTEXT_CORE_PROFILE_BIT;
import static org.lwjgl.opengl.GL32C.GL_CONTEXT_PROFILE_MASK;
import static org.lwjgl.opengl.WGLARBCreateContext.WGL_CONTEXT_MAJOR_VERSION_ARB;
import static org.lwjgl.opengl.WGLARBCreateContext.WGL_CONTEXT_MINOR_VERSION_ARB;
import static org.lwjgl.opengl.WGLARBCreateContext.wglCreateContextAttribsARB;
import static org.lwjgl.opengl.WGLARBCreateContextProfile.WGL_CONTEXT_CORE_PROFILE_BIT_ARB;
import static org.lwjgl.opengl.WGLARBCreateContextProfile.WGL_CONTEXT_PROFILE_MASK_ARB;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.nio.IntBuffer;
import java.util.Objects;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.WGL;
import org.lwjgl.opengl.WGLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.windows.GDI32;
import org.lwjgl.system.windows.MSG;
import org.lwjgl.system.windows.PIXELFORMATDESCRIPTOR;
import org.lwjgl.system.windows.User32;
import org.lwjgl.system.windows.WNDCLASSEX;
import org.lwjgl.system.windows.WindowsLibrary;

/**
 * The Windows context: a hidden window, a legacy WGL context to reach {@code wglCreateContextAttribsARB}, then
 * the real core context on the same window (ADR-0002). The legacy context is deleted as soon as the core one is
 * current. Nothing is ever drawn to the window: the renderer draws into framebuffer objects, so the window's
 * pixel format only has to support OpenGL and there is no need for the usual second, dummy window.
 *
 * <p>All of it runs on the creating thread, which owns the window (Win32 window affinity) and the context.
 */
final class WglContext implements GlContext {

    private static final String WINDOW_CLASS = "AndysDiceGL";

    // Set once the window class is registered; it stays registered for the process lifetime.
    private static boolean classRegistered;

    private final long window;
    private final long dc;
    private final long context;
    private final GlDriver driver;
    private final MSG message = MSG.calloc();
    private boolean closed;

    private WglContext(long window, long dc, long context, GlDriver driver) {
        this.window = window;
        this.dc = dc;
        this.context = context;
        this.driver = driver;
    }

    /**
     * Makes the hidden window and a core context of at least {@code required}, current on this thread.
     *
     * @param required the lowest acceptable core version
     * @return the current context
     * @throws GlUnavailableException if the window, a pixel format, the legacy context or the core context can't
     *     be made, or the core context turns out older than asked or not core
     */
    static WglContext create(GlVersion required) {
        loadOpenGl(required);
        long window = NULL;
        long dc = NULL;
        long legacy = NULL;
        long core = NULL;
        GlDriver driver = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer lastError = stack.mallocInt(1);
            registerWindowClass(required);
            window = User32.CreateWindowEx(lastError, User32.WS_EX_TOOLWINDOW, WINDOW_CLASS, "Andy's Dice GL",
                    User32.WS_OVERLAPPEDWINDOW | User32.WS_CLIPSIBLINGS | User32.WS_CLIPCHILDREN, 0, 0, 1, 1,
                    NULL, NULL, WindowsLibrary.HINSTANCE, NULL);
            if (window == NULL) {
                throw fail(required, "its hidden window couldn't be made (Win32 error " + lastError.get(0) + ")",
                        null);
            }
            dc = User32.GetDC(window);
            PIXELFORMATDESCRIPTOR pfd = PIXELFORMATDESCRIPTOR.calloc(stack)
                    .nSize((short) PIXELFORMATDESCRIPTOR.SIZEOF)
                    .nVersion((short) 1)
                    .dwFlags(GDI32.PFD_DRAW_TO_WINDOW | GDI32.PFD_SUPPORT_OPENGL | GDI32.PFD_DOUBLEBUFFER)
                    .iPixelType(GDI32.PFD_TYPE_RGBA)
                    .cColorBits((byte) 32)
                    .cDepthBits((byte) 24)
                    .cStencilBits((byte) 8)
                    .iLayerType(GDI32.PFD_MAIN_PLANE);
            int format = GDI32.ChoosePixelFormat(lastError, dc, pfd);
            if (format == 0 || !GDI32.SetPixelFormat(lastError, dc, format, pfd)) {
                throw fail(required, "the display offers no OpenGL pixel format (Win32 error " + lastError.get(0)
                        + ")", null);
            }

            legacy = WGL.wglCreateContext(lastError, dc);
            if (legacy == NULL || !WGL.wglMakeCurrent(lastError, dc, legacy)) {
                throw fail(required, "the driver couldn't make any OpenGL context (Win32 error " + lastError.get(0)
                        + ")", null);
            }
            GL.createCapabilities();
            driver = askDriver();
            GlVersion offered = GlVersion.parse(driver.version()).orElse(null);
            WGLCapabilities wgl = GL.createCapabilitiesWGL();
            if (!wgl.WGL_ARB_create_context || !wgl.WGL_ARB_create_context_profile) {
                throw fail(required, offered == null || !offered.isAtLeast(required)
                        ? "the driver offers only OpenGL " + driver.version()
                        : "the driver can't create versioned core contexts (no WGL_ARB_create_context_profile)",
                        driver);
            }

            core = wglCreateContextAttribsARB(dc, NULL, new int[] {
                WGL_CONTEXT_MAJOR_VERSION_ARB, required.major(),
                WGL_CONTEXT_MINOR_VERSION_ARB, required.minor(),
                WGL_CONTEXT_PROFILE_MASK_ARB, WGL_CONTEXT_CORE_PROFILE_BIT_ARB,
                0,
            });
            if (core == NULL) {
                throw fail(required, offered == null || !offered.isAtLeast(required)
                        ? "the driver offers only OpenGL " + driver.version()
                        : "the driver refused an OpenGL " + required + " core context", driver);
            }
            if (!WGL.wglMakeCurrent(lastError, dc, core)) {
                throw fail(required, "the new core context couldn't be made current (Win32 error "
                        + lastError.get(0) + ")", driver);
            }
            WGL.wglDeleteContext(null, legacy);
            legacy = NULL;

            GLCapabilities caps = GL.createCapabilities();
            driver = askDriver();
            GlVersion got = GlVersion.parse(driver.version()).orElse(null);
            if (got == null || !got.isAtLeast(required) || !caps.OpenGL32
                    || (glGetInteger(GL_CONTEXT_PROFILE_MASK) & GL_CONTEXT_CORE_PROFILE_BIT) == 0) {
                throw fail(required, "the driver's context is OpenGL " + driver.version() + ", not "
                        + required + " core", driver);
            }
            return new WglContext(window, dc, core, driver);
        } catch (GlUnavailableException e) {
            release(window, dc, legacy, core);
            throw e;
        } catch (RuntimeException e) {
            // LWJGL reports a missing entry point or a native failure as an unchecked exception; it is still
            // "no usable context" to the caller, with the original as the cause.
            release(window, dc, legacy, core);
            throw new GlUnavailableException(required, "making the context failed: " + e.getMessage(), driver, e);
        }
    }

    @Override
    public GlDriver driver() {
        return driver;
    }

    @Override
    public void pumpEvents() {
        // Messages for the hidden window go to DefWindowProc. NULL picks up every window of this thread.
        while (User32.PeekMessage(message, NULL, 0, 0, User32.PM_REMOVE)) {
            User32.TranslateMessage(message);
            User32.DispatchMessage(message);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        release(window, dc, NULL, context);
        message.free();
    }

    /** Loads opengl32.dll once per process; LWJGL refuses a second {@code GL.create()}. */
    private static synchronized void loadOpenGl(GlVersion required) {
        try {
            if (GL.getFunctionProvider() == null) {
                GL.create();
            }
        } catch (RuntimeException | LinkageError e) {
            // LinkageError too: a missing lwjgl_opengl native, or org.lwjgl.opengl lacking native access under
            // deny, fails GL's static initialiser, and the fail-fast must still say why.
            throw new GlUnavailableException(required, "the OpenGL library couldn't be loaded: " + e, null, e);
        }
    }

    private static synchronized void registerWindowClass(GlVersion required) {
        if (classRegistered) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            WNDCLASSEX wc = WNDCLASSEX.calloc(stack)
                    .cbSize(WNDCLASSEX.SIZEOF)
                    // CS_OWNDC: the window keeps one device context for its lifetime, as WGL wants.
                    .style(User32.CS_OWNDC)
                    .hInstance(WindowsLibrary.HINSTANCE)
                    .lpszClassName(stack.UTF16(WINDOW_CLASS));
            // The window procedure is User32's own DefWindowProc, stored as a raw function pointer rather than
            // a Java WindowProc: no upcall stub is made and no Java code ever runs inside a window message.
            // (LWJGL's WindowProc.create(long) only wraps callbacks LWJGL made itself, so it can't be used.)
            MemoryUtil.memPutAddress(wc.address() + WNDCLASSEX.LPFNWNDPROC, User32.Functions.DefWindowProc);
            IntBuffer lastError = stack.mallocInt(1);
            if (User32.RegisterClassEx(lastError, wc) == 0) {
                throw fail(required, "its window class couldn't be registered (Win32 error " + lastError.get(0)
                        + ")", null);
            }
            classRegistered = true;
        }
    }

    private static GlDriver askDriver() {
        return new GlDriver(Objects.toString(glGetString(GL_RENDERER), "unknown"),
                Objects.toString(glGetString(GL_VENDOR), "unknown"),
                Objects.toString(glGetString(GL_VERSION), "unknown"));
    }

    /** Undoes whatever {@link #create} got as far as, in reverse. Every argument may be {@code NULL}. */
    private static void release(long window, long dc, long legacy, long core) {
        WGL.wglMakeCurrent(null, NULL, NULL);
        GL.setCapabilities(null);
        if (core != NULL) {
            WGL.wglDeleteContext(null, core);
        }
        if (legacy != NULL) {
            WGL.wglDeleteContext(null, legacy);
        }
        if (dc != NULL) {
            User32.ReleaseDC(window, dc);
        }
        if (window != NULL) {
            User32.DestroyWindow(null, window);
        }
    }

    private static GlUnavailableException fail(GlVersion required, String reason, GlDriver driver) {
        return new GlUnavailableException(required, reason, driver, null);
    }
}
