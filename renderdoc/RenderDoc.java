package renderdoc;

import com.mojang.logging.LogUtils;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.Util;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.linux.DynamicLinkLoader;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;

@ApiStatus.Experimental
@SuppressWarnings({ "unused", "UnusedReturnValue" })
public final class RenderDoc {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RenderDocLibrary.RenderdocApi API;

    private static final String LINUX_RENDER_DOC_WARNING = """

            ========================================
            Ignored 'dev.renderDocPath' property as this Minecraft instance is not running on Windows.
            Please populate the LD_PRELOAD environment variable instead
            ========================================""";

    private static final String MAC_RENDER_DOC_WARNING = """

            ========================================
            Ignored 'dev.renderDocPath' property as this Minecraft instance is not running on Windows.
            RenderDoc is not supported on macOS
            ========================================""";

    private static final String GENERIC_RENDER_DOC_WARNING = """

            ========================================
            Ignored 'dev.renderDocPath' property as this Minecraft instance is not running on Windows.
            ========================================""";

    static {
        final var renderDocPath = System.getProperty("dev.renderDocPath");
        if (renderDocPath != null) {
            if (Util.getPlatform() == Util.OS.WINDOWS) {
                System.load(renderDocPath);
            } else {
                LOGGER.warn(switch (Util.getPlatform()) {
                    case LINUX -> LINUX_RENDER_DOC_WARNING;
                    case OSX -> MAC_RENDER_DOC_WARNING;
                    default -> GENERIC_RENDER_DOC_WARNING;
                });
            }
        }

        var apiPointer = new PointerByReference();
        RenderDocLibrary.RenderdocApi apiInstance = null;

        var os = Util.getPlatform();

        if (os == Util.OS.WINDOWS || os == Util.OS.LINUX) {
            try {
                RenderDocLibrary renderdocLibrary;
                if (os == Util.OS.WINDOWS) {
                    renderdocLibrary = Native.load("renderdoc", RenderDocLibrary.class);
                } else {
                    int flags = DynamicLinkLoader.RTLD_NOW | DynamicLinkLoader.RTLD_NOLOAD;
                    if (DynamicLinkLoader.dlopen("librenderdoc.so", flags) == 0) {
                        throw new UnsatisfiedLinkError();
                    }

                    renderdocLibrary = Native.load("renderdoc", RenderDocLibrary.class,
                            Map.of(Library.OPTION_OPEN_FLAGS, flags));
                }

                int initResult = renderdocLibrary.RENDERDOC_GetAPI(10500, apiPointer);
                if (initResult != 1) {
                    LOGGER.error("Could not connect to RenderDoc API, return code: {}", initResult);
                } else {
                    apiInstance = new RenderDocLibrary.RenderdocApi(apiPointer.getValue());

                    var major = new IntByReference();
                    var minor = new IntByReference();
                    var patch = new IntByReference();
                    apiInstance.GetAPIVersion.call(major, minor, patch);
                    LOGGER.info("Connected to RenderDoc API v{}.{}.{}", major.getValue(), minor.getValue(), patch.getValue());
                }
            } catch (UnsatisfiedLinkError ignored) {}
        }

        API = apiInstance;
    }

    /**
     * @return {@code true} if the RenderDoc dynamic library is loaded
     *         and owo has successfully connected to the API
     */
    public static boolean isAvailable() {
        return API != null;
    }

    /**
     * @return The version of the RenderDoc API that owo is connected to,
     *         in &lt;major&gt;.&lt;minor&gt;.&lt;patch&gt; semver format
     */
    public static String getAPIVersion() {
        if (API == null) return "not connected";

        var major = new IntByReference();
        var minor = new IntByReference();
        var patch = new IntByReference();
        API.GetAPIVersion.call(major, minor, patch);

        return major.getValue() + "." + minor.getValue() + "." + patch.getValue();
    }

    /**
     * Set the value of a RenderDoc capture option
     *
     * @param option The option to modify
     * @param value  The value to change the option to
     * @return {@code true} if the value was correct and the option
     *         was successfully modified
     */
    public static <T> boolean setCaptureOption(CaptureOption<T> option, T value) {
        if (API == null) return false;

        if (value instanceof Boolean bool) {
            return API.SetCaptureOptionU32.call(option.idx, new RenderDocLibrary.uint32_t(bool ? 1 : 0)) == 1;
        } else if (value instanceof Integer uint) {
            return API.SetCaptureOptionU32.call(option.idx, new RenderDocLibrary.uint32_t(uint)) == 1;
        } else {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Get the value of a RenderDoc capture option
     *
     * @param option The option to query
     * @return The current value of the option
     */
    @SuppressWarnings("unchecked")
    public static <T> T getCaptureOption(CaptureOption<T> option) {
        if (API == null) return null;

        if (option.type == Boolean.class) {
            return (T) Boolean.valueOf(API.GetCaptureOptionU32.call(option.idx).intValue() == 1);
        } else if (option.type == Integer.class) {
            return (T) Integer.valueOf(API.GetCaptureOptionU32.call(option.idx).intValue());
        } else {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Set the hotkeys used to trigger a capture
     */
    public static void setCaptureKeys(Key... keys) {
        if (API == null) return;
        API.SetCaptureKeys.call(Arrays.stream(keys).mapToInt(value -> value.keycode).toArray(), keys.length);
    }

    /**
     * Query the current configuration of the RenderDoc overlay
     *
     * @return All parts of the overlay which are currently enabled
     */
    public static EnumSet<OverlayOption> getOverlayOptions() {
        if (API == null) return null;

        int mask = API.GetOverlayBits.call().intValue();

        var set = EnumSet.noneOf(OverlayOption.class);
        for (var option : OverlayOption.values()) {
            if ((mask & option.mask) != 0) set.add(option);
        }

        return set;
    }

    /**
     * Enable some parts of the RenderDoc overlay
     *
     * @param options The options to enable
     */
    public static void enableOverlayOptions(OverlayOption... options) {
        if (API == null) return;

        int mask = 0;
        for (var option : options) mask |= option.mask;

        API.MaskOverlayBits.call(new RenderDocLibrary.uint32_t(~0), new RenderDocLibrary.uint32_t(mask));
    }

    /**
     * Disable some parts of the RenderDoc overlay
     *
     * @param options The options to enable
     */
    public static void disableOverlayOptions(OverlayOption... options) {
        if (API == null) return;

        int mask = 0;
        for (var option : options) mask |= option.mask;

        API.MaskOverlayBits.call(new RenderDocLibrary.uint32_t(~mask), new RenderDocLibrary.uint32_t(0));
    }

    /**
     * Try to remove all RenderDoc hooks from the process. If this
     * is called after a graphics API has been initialized, behavior
     * is undefined
     */
    public static void removeHooks() {
        if (API == null) return;
        API.RemoveHooks.call();
    }

    /**
     * Remove RenderDoc's crash handler from the process
     */
    public static void unloadCrashHandler() {
        if (API == null) return;
        API.UnloadCrashHandler.call();
    }

    /**
     * Set the template used to generate new capture file names
     */
    public static void setCaptureFilePathTemplate(String template) {
        if (API == null) return;
        API.SetCaptureFilePathTemplate.call(template);
    }

    /**
     * @return the template used to generate new capture file names
     */
    public static String getCaptureFilePathTemplate() {
        if (API == null) return null;
        return API.GetCaptureFilePathTemplate.call();
    }

    /**
     * Query information about a specific capture
     *
     * @param index The index to query
     * @return The path and timestamp of the capture at the given index,
     *         or {@code null} if no such capture exists
     */
    public static Capture getCapture(int index) {
        if (API == null) return null;

        var length = new IntByReference();
        if (API.GetCapture.call(index, null, length, null).intValue() != 1) {
            return null;
        }

        var filename = new byte[length.getValue()];
        var timestamp = new LongByReference();

        API.GetCapture.call(index, filename, length, timestamp);
        return new Capture(new String(filename, 0, filename.length - 1), Instant.ofEpochSecond(timestamp.getValue()));
    }

    /**
     * @return How many captures have been made
     */
    public static int getNumCaptures() {
        if (API == null) return -1;
        return API.GetNumCaptures.call().intValue();
    }

    /**
     * Trigger a capture of the next frame, as
     * if the user had pressed on the capture hotkeys
     */
    public static void triggerCapture() {
        if (API == null) return;
        API.TriggerCapture.call();
    }

    /**
     * Immediately begin a capture
     */
    public static void startFrameCapture() {
        if (API == null) return;
        API.StartFrameCapture.call(null, null);
    }

    /**
     * @return {@code true} if a capture is currently being performed
     */
    public static boolean isFrameCapturing() {
        if (API == null) return false;
        return API.IsFrameCapturing.call().intValue() == 1;
    }

    /**
     * Immediately end an active capture
     */
    public static void endFrameCapture() {
        if (API == null) return;
        API.EndFrameCapture.call(null, null);
    }

    /**
     * @return {@code true} if a RenderDoc replay UI
     *         instance is currently attached to this process
     */
    public static boolean isReplayUIConnected() {
        if (API == null) return false;
        return API.IsTargetControlConnected.call().intValue() == 1;
    }

    /**
     * Open the RenderDoc replay UI
     *
     * @param connect {@code true} if the new UI instance should instantly
     *                attach to this process
     * @return The PID of the spawned process, or {@code 0} if the UI could not be opened
     */
    public static int launchReplayUI(boolean connect) {
        if (API == null) return -1;
        return API.LaunchReplayUI.call(new RenderDocLibrary.uint32_t(connect ? 1 : 0), null).intValue();
    }

    /**
     * Request the currently connected replay UI to raise
     * its window to the top - this is not guaranteed to work on every OS
     *
     * @return {@code true} if the UI tried to raise its window, {@code false}
     *         if some error occurred while passing on the command or no UI is connected
     */
    public static boolean showReplayUI() {
        if (API == null) return false;
        return API.ShowReplayUI.call().intValue() == 1;
    }

    /**
     * Set the comments attached to a specific capture
     *
     * @param capture  The capture to modify, obtain with {@link #getCapture(int)}
     * @param comments The new capture comments
     */
    public static void setCaptureComments(Capture capture, String comments) {
        if (API == null) return;
        API.SetCaptureFileComments.call(capture.path, comments);
    }

    private RenderDoc() {}

    public static final class CaptureOption<T> {

        public static final CaptureOption<Boolean> ALLOW_VSYNC = new CaptureOption<>(0, Boolean.class);
        public static final CaptureOption<Boolean> ALLOW_FULLSCREEN = new CaptureOption<>(1, Boolean.class);
        public static final CaptureOption<Boolean> API_VALIDATION = new CaptureOption<>(2, Boolean.class);
        public static final CaptureOption<Boolean> CAPTURE_CALLSTACKS = new CaptureOption<>(3, Boolean.class);
        public static final CaptureOption<Boolean> CAPTURE_CALLSTACKS_ONLY_DRAWS = new CaptureOption<>(4,
                Boolean.class);
        public static final CaptureOption<Integer> DELAY_FOR_DEBUGGER = new CaptureOption<>(5, Integer.class);
        public static final CaptureOption<Boolean> VERIFY_BUFFER_ACCESS = new CaptureOption<>(6, Boolean.class);
        public static final CaptureOption<Boolean> HOOK_INTO_CHILDREN = new CaptureOption<>(7, Boolean.class);
        public static final CaptureOption<Boolean> REF_ALL_RESOURCES = new CaptureOption<>(8, Boolean.class);
        public static final CaptureOption<Boolean> SAVE_ALL_INITIALS = new CaptureOption<>(9, Boolean.class);
        public static final CaptureOption<Boolean> CAPTURE_ALL_CMD_LISTS = new CaptureOption<>(10, Boolean.class);
        public static final CaptureOption<Boolean> DEBUG_OUTPUT_MUTE = new CaptureOption<>(11, Boolean.class);

        @Deprecated
        public static final CaptureOption<?> ALLOW_UNSUPPORTED_VENDOR_EXTENSIONS = new CaptureOption<>(12, Void.class);

        public final int idx;
        private final Class<T> type;

        CaptureOption(int idx, Class<T> type) {
            this.idx = idx;
            this.type = type;
        }
    }

    public enum Key {

        // '0' - '9' matches ASCII values
        ZERO(0x30, GLFW.GLFW_KEY_0),
        ONE(0x31, GLFW.GLFW_KEY_1),
        TWO(0x32, GLFW.GLFW_KEY_2),
        THREE(0x33, GLFW.GLFW_KEY_2),
        FOUR(0x34, GLFW.GLFW_KEY_4),
        FIVE(0x35, GLFW.GLFW_KEY_5),
        SIX(0x36, GLFW.GLFW_KEY_6),
        SEVEN(0x37, GLFW.GLFW_KEY_7),
        EIGHT(0x38, GLFW.GLFW_KEY_8),
        NINE(0x39, GLFW.GLFW_KEY_9),

        // 'A' - 'Z' matches ASCII values
        A(0x41, GLFW.GLFW_KEY_A),
        B(0x42, GLFW.GLFW_KEY_B),
        C(0x43, GLFW.GLFW_KEY_C),
        D(0x44, GLFW.GLFW_KEY_D),
        E(0x45, GLFW.GLFW_KEY_E),
        F(0x46, GLFW.GLFW_KEY_F),
        G(0x47, GLFW.GLFW_KEY_G),
        H(0x48, GLFW.GLFW_KEY_H),
        I(0x49, GLFW.GLFW_KEY_I),
        J(0x4A, GLFW.GLFW_KEY_J),
        K(0x4B, GLFW.GLFW_KEY_K),
        L(0x4C, GLFW.GLFW_KEY_L),
        M(0x4D, GLFW.GLFW_KEY_M),
        N(0x4E, GLFW.GLFW_KEY_N),
        O(0x4F, GLFW.GLFW_KEY_O),
        P(0x50, GLFW.GLFW_KEY_P),
        Q(0x51, GLFW.GLFW_KEY_Q),
        R(0x52, GLFW.GLFW_KEY_R),
        S(0x53, GLFW.GLFW_KEY_S),
        T(0x54, GLFW.GLFW_KEY_T),
        U(0x55, GLFW.GLFW_KEY_U),
        V(0x56, GLFW.GLFW_KEY_V),
        W(0x57, GLFW.GLFW_KEY_W),
        X(0x58, GLFW.GLFW_KEY_X),
        Y(0x59, GLFW.GLFW_KEY_Y),
        Z(0x5A, GLFW.GLFW_KEY_Z),

        // leave the rest of the ASCII range free
        // in case we want to use it later
        NON_PRINTABLE(0x100, -1),

        DIVIDE(0x101, GLFW.GLFW_KEY_KP_DIVIDE),
        MULTIPLY(0x102, GLFW.GLFW_KEY_KP_MULTIPLY),
        SUBTRACT(0x103, GLFW.GLFW_KEY_KP_SUBTRACT),
        PLUS(0x104, GLFW.GLFW_KEY_KP_ADD),

        F1(0x105, GLFW.GLFW_KEY_F1),
        F2(0x106, GLFW.GLFW_KEY_F2),
        F3(0x107, GLFW.GLFW_KEY_F3),
        F4(0x108, GLFW.GLFW_KEY_F4),
        F5(0x109, GLFW.GLFW_KEY_F5),
        F6(0x10a, GLFW.GLFW_KEY_F6),
        F7(0x10b, GLFW.GLFW_KEY_F7),
        F8(0x10c, GLFW.GLFW_KEY_F8),
        F9(0x10d, GLFW.GLFW_KEY_F9),
        F10(0x10e, GLFW.GLFW_KEY_F10),
        F11(0x10f, GLFW.GLFW_KEY_F11),
        F12(0x110, GLFW.GLFW_KEY_F12),

        HOME(0x111, GLFW.GLFW_KEY_HOME),
        END(0x112, GLFW.GLFW_KEY_END),
        INSERT(0x113, GLFW.GLFW_KEY_INSERT),
        DELETE(0x114, GLFW.GLFW_KEY_DELETE),
        PAGE_UP(0x115, GLFW.GLFW_KEY_PAGE_UP),
        PAGE_DOWN(0x116, GLFW.GLFW_KEY_PAGE_DOWN),

        BACKSPACE(0x117, GLFW.GLFW_KEY_BACKSPACE),
        TAB(0x118, GLFW.GLFW_KEY_TAB),
        PRINT_SCREEN(0x119, GLFW.GLFW_KEY_PRINT_SCREEN),
        PAUSE(0x11a, GLFW.GLFW_KEY_PAUSE);

        private final int keycode;
        private final int glfw;

        Key(int keycode, int glfw) {
            this.keycode = keycode;
            this.glfw = glfw;
        }

        private static final Int2ObjectMap<Key> GLFW_MAPPINGS = new Int2ObjectOpenHashMap<>();

        public static @Nullable Key fromGLFW(int glfw) {
            return GLFW_MAPPINGS.getOrDefault(glfw, null);
        }

        static {
            for (var key : values()) {
                if (key.glfw < 0) continue;
                GLFW_MAPPINGS.put(key.glfw, key);
            }
        }
    }

    public enum OverlayOption {

        ENABLED(0x1),
        FRAME_RATE(0x2),
        FRAME_NUMBER(0x4),
        CAPTURE_LIST(0x8),
        DEFAULT(ENABLED.mask | FRAME_RATE.mask | FRAME_NUMBER.mask | CAPTURE_LIST.mask),
        ALL(~0),
        NONE(0);

        public final int mask;

        OverlayOption(int mask) {
            this.mask = mask;
        }
    }

    public record Capture(String path, Instant timestamp) {}
}
