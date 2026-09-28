package net.kdt.pojavlaunch.utils;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GLInfoUtils {
    public static String GLES_VERSION_PREFIX = "OpenGL ES ";
    private static GLInfo info;

    private static int[] getGLVersion(String versionString) {
        if(versionString == null) throw new NumberFormatException("GL version is null");
        if(versionString.startsWith(GLES_VERSION_PREFIX)) {
            versionString = versionString.substring(GLES_VERSION_PREFIX.length());
        }

        Matcher matcher = Pattern.compile("(\\d+)\\.(\\d+)").matcher(versionString);
        if(!matcher.find()) {
            throw new NumberFormatException("Could not parse GL version: " + versionString);
        }
        return new int[] {
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2))
        };
    }

    private static GLInfo queryInfo(int contextGLVersion, boolean forcedMsaa) {
        String vendor = GLES20.glGetString(GLES20.GL_VENDOR);
        String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
        String versionString = GLES20.glGetString(GLES30.GL_VERSION);
        int majorVersion = 2;
        int minorVersion = 0;
        try {
            int[] parsedVersion = getGLVersion(versionString);
            majorVersion = parsedVersion[0];
            minorVersion = parsedVersion[1];
        }catch (NumberFormatException e) {
            Log.w("GLInfoUtils","Failed to parse GL version number, falling back to 2.0", e);
        }
        // LTW/MobileGlues depend on the ability to create a context with a major version of 3,
        // and even if the string parse returns 3 while EGL can only create 2,
        // it's still a noncompliant implementation.
        if(majorVersion > contextGLVersion) {
            majorVersion = contextGLVersion;
            minorVersion = 0;
        }
        return new GLInfo(vendor, renderer, majorVersion, minorVersion, forcedMsaa);
    }

    private static void initDummyInfo() {
        Log.e("GLInfoUtils", "An error happened during info query. Will use dummy info. This should be investigated.");
        info = new GLInfo("<Unknown>", "<Unknown>", 2, 0, false);
    }

    private static EGLContext tryCreateContext(EGLDisplay eglDisplay, EGLConfig config, int majorVersion) {
        int[] egl_context_attributes = new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, majorVersion, EGL14.EGL_NONE };
        EGLContext context = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, egl_context_attributes, 0);
        if(EGL14.EGL_NO_CONTEXT.equals(context) || context == null) {
            Log.e("GLInfoUtils", "Failed to create a context with major version "+majorVersion);
            return null;
        }
        return context;
    }

    private static EGLContext tryMakeCurrent(EGLDisplay eglDisplay, EGLConfig config, EGLSurface surface, int majorVersion) {
        EGLContext context = tryCreateContext(eglDisplay, config, majorVersion);
        if(context == null) return null;
        // Old Mali drivers are broken, and will actually let us create a context with GLES 3
        // But won't let us make it current, which will break the check anyway...
        boolean makeCurrentResult = EGL14.eglMakeCurrent(eglDisplay, surface, surface, context);
        if(!makeCurrentResult) {
            Log.i("GLInfoUtils", "Failed to make context GL version "+majorVersion +" current");
            EGL14.eglDestroyContext(eglDisplay, context);
            return null;
        }
        return context;
    }

    private static boolean isMSAAConfig(EGLDisplay eglDisplay, EGLConfig eglConfig) {
        int[] sampleBuffers = new int[]{0};
        EGL14.eglGetConfigAttrib(eglDisplay, eglConfig, EGL14.EGL_SAMPLE_BUFFERS, sampleBuffers, 0);
        return sampleBuffers[0] != 0;
    }

    private static boolean initAndQueryInfo() {
        // This is here just to satisfy Android M which incorrectly null-checks it
        int[] egl_version = new int[2];
        EGLDisplay eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if(eglDisplay == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(eglDisplay, egl_version, 0 , egl_version, 1)) return false;
        int[] egl_attributes = new int[]  {
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 24,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] config = new EGLConfig[1];
        int[] num_configs = new int[]{0};
        if(!EGL14.eglChooseConfig(eglDisplay, egl_attributes, 0, config, 0, 1, num_configs, 0) || num_configs[0] == 0) {
            EGL14.eglTerminate(eglDisplay);
            Log.e("GLInfoUtils", "Failed to choose an EGL config");
            return false;
        }

        boolean forcedMsaa = isMSAAConfig(eglDisplay, config[0]);

        // Create PBuffer surface as some devices might actually not support surfaceless.
        int[] pbuffer_attributes = new int[] {
                EGL14.EGL_WIDTH, 16,
                EGL14.EGL_HEIGHT, 16,
                EGL14.EGL_NONE
        };

        EGLSurface surface = EGL14.eglCreatePbufferSurface(eglDisplay, config[0], pbuffer_attributes, 0);
        if(surface == null || surface == EGL14.EGL_NO_SURFACE) {
            Log.e("GLInfoUtils", "Failed to create pbuffer surface");
            EGL14.eglTerminate(eglDisplay);
            return false;
        }

        int contextGLVersion = 3;
        EGLContext context = tryMakeCurrent(eglDisplay, config[0], surface, contextGLVersion);
        if(context == null) {
            contextGLVersion = 2;
            context = tryMakeCurrent(eglDisplay, config[0], surface, contextGLVersion);
        }

        // Creation/currenting failed in both cases
        if(context == null) {
            Log.e("GLInfoUtils", "Failed to create and make context current");
            EGL14.eglDestroySurface(eglDisplay, surface);
            EGL14.eglTerminate(eglDisplay);
            return false;
        }

        info = queryInfo(contextGLVersion, forcedMsaa);

        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroyContext(eglDisplay, context);
        EGL14.eglTerminate(eglDisplay);
        return true;
    }

    /**
     * Get the information about the current OpenGL ES device, which consists of the vendor,
     * the renderer and the major GLES version
     * @return the info
     */
    public static GLInfo getGlInfo() {
        if(info != null) return info;
        Log.i("GLInfoUtils", "Querying graphics device info...");
        boolean infoQueryResult = false;
        try {
            infoQueryResult = initAndQueryInfo();
        }catch (Throwable e) {
            Log.e("GLInfoUtils", "Throwable when trying to initialize GL info", e);
        }
        if(!infoQueryResult) initDummyInfo();
        return info;
    }

    public static class GLInfo {
        public final String vendor;
        public final String renderer;
        public final int glesMajorVersion;
        public final int glesMinorVersion;
        public final boolean forcedMsaa;
        protected GLInfo(String vendor, String renderer, int glesMajorVersion,
                         int glesMinorVersion, boolean forcedMsaa) {
            this.vendor = vendor;
            this.renderer = renderer;
            this.glesMajorVersion = glesMajorVersion;
            this.glesMinorVersion = glesMinorVersion;
            this.forcedMsaa = forcedMsaa;
        }

        public boolean supportsGles31() {
            return glesMajorVersion > 3 ||
                    (glesMajorVersion == 3 && glesMinorVersion >= 1);
        }

        /**
         * Check if this GLInfo belongs to a Qualcomm Adreno graphics adapter
         * @return
         */
        public boolean isAdreno() {
            return renderer.contains("Adreno") && vendor.contains("Qualcomm");
        }

        /**
         * Check if this GLInfo belongs to a Qualcomm Adreno 200/300/400/500 graphics adapter
         * @return
         */
        public boolean isAdreno500Lower(){
            return vendor.contains("Qualcomm") &&
                    (renderer.contains("Adreno (TM) 5") ||
                    renderer.contains("Adreno (TM) 4") ||
                    renderer.contains("Adreno (TM) 3") ||
                    renderer.contains("Adreno (TM) 2"));
        }

        /**
         * Check if this GLInfo belongs to a ARM Mali/Immortalis graphics adapter
         * @return
         */
        public boolean isArm() {
            return (renderer.contains("Mali") || renderer.contains("Immortalis")) && vendor.equals("ARM");
        }
    }
}
