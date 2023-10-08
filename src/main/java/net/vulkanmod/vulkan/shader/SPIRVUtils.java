package net.vulkanmod.vulkan.shader;

import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeResource;
import org.lwjgl.util.shaderc.ShadercIncludeResolveI;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultReleaseI;
import org.lwjgl.vulkan.VK12;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

import static net.vulkanmod.Initializer.LOGGER;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memASCII;
import static org.lwjgl.util.shaderc.Shaderc.*;

public class SPIRVUtils {
    private static final boolean DEBUG = true;
    private static final boolean OPTIMIZATIONS = false;

    private static final long compiler;

    static final long options;
    //The dedicated Includer and Releaser Inner Classes used to Initialise #include SUpprt for ShaderC
    private static final ShaderIncluder SHADER_INCLUDER = new ShaderIncluder();
    private static final ShaderReleaser SHADER_RELEASER = new ShaderReleaser();

    private static final long pUserData = 0;
    private static final boolean skipCompilation = !Vulkan.ENABLE_VALIDATION_LAYERS;
    //Must Init compiler handle in static block to allow use of the Includer
    //(So the Includer+Releaser isn't recreated redundantly over and over for each Shader Compile)
    static {

        compiler = shaderc_compiler_initialize();

        if(compiler == NULL) {
            throw new RuntimeException("Failed to create shader compiler");
        }

        options = shaderc_compile_options_initialize();

        if(options == NULL) {
            throw new RuntimeException("Failed to create compiler options");
        }

        if(OPTIMIZATIONS) {
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
        }

        if(DEBUG)
            shaderc_compile_options_set_generate_debug_info(options);

        shaderc_compile_options_set_target_env(options, shaderc_env_version_vulkan_1_2, VK12.VK_API_VERSION_1_2);
        shaderc_compile_options_set_include_callbacks(options, SHADER_INCLUDER, SHADER_RELEASER, pUserData);

    }

    public static SPIRV compileShaderAbsoluteFile(String shaderFile, ShaderKind shaderKind) {
        try {
            String source = new String(Files.readAllBytes(Paths.get(new URI(shaderFile))));
            return compileShader(shaderFile, source, shaderKind);
        } catch (IOException | URISyntaxException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static SPIRV compileShader(String filename, String source, ShaderKind shaderKind) {


        long result = shaderc_compile_into_spv(compiler, source, shaderKind.kind, filename, "main", options);

        if(result == NULL) {
            throw new RuntimeException("Failed to compile shader " + filename + " into SPIR-V");
        }

        if(shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
            throw new RuntimeException("Failed to compile shader " + filename + " into SPIR-V:\n" + shaderc_result_get_error_message(result));
        }

        return new SPIRV(result, (int) shaderc_result_get_length(result));
    }

    private static SPIRV readFromStream(InputStream inputStream) {
        try {
            byte[] bytes = inputStream.readAllBytes();
            ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
            buffer.put(bytes);
            buffer.position(0);

            return new SPIRV(MemoryUtil.memAddress(buffer), bytes.length);
        } catch (Exception e) {
            e.printStackTrace();
        }
        throw new RuntimeException("unable to read inputStream");
    }

    public enum ShaderKind {
        VERTEX_SHADER(shaderc_glsl_vertex_shader),
        GEOMETRY_SHADER(shaderc_glsl_geometry_shader),
        FRAGMENT_SHADER(shaderc_glsl_fragment_shader),
        COMPUTE_SHADER(shaderc_glsl_compute_shader);

        private final int kind;

        ShaderKind(int kind) {
            this.kind = kind;
        }
    }

    public record SPIRV(long handle, int size_t) implements NativeResource {

        public ByteBuffer bytecode() {
            return shaderc_result_get_bytes(handle, size_t);
        }

        @Override
        public void free() {
            shaderc_result_release(handle);
//            bytecode = null; // Help the GC
        }
    }

    private static class ShaderIncluder implements ShadercIncludeResolveI {

        private static final int MAX_PATH_LENGTH = 4096; //Maximum Linux/Unix Path Length

        @Override
        public long invoke(long user_data, long requested_source, int type, long requesting_source, long include_depth) {
            //TODO: try to optimise this if it gets too slow/(i.e. causes too much CPU overhead, if any that is)
            // MemASCII is also much faster than memUTF8 (Due to less Glyphs.Identifiers for unique Chars)
            var s = memASCII(requested_source);
            var s1 = memASCII(requesting_source+5); //Strip the "file:/" prefix from the initial string const char* Address
            LOGGER.info("Invoking Includer!:"+ " Source: "+s + " Header: "+s1);
            try(MemoryStack stack = MemoryStack.stackPush(); FileInputStream fileInputStream = new FileInputStream(s1.substring(0, s1.lastIndexOf("/")+1) + s))
            {
                return ShadercIncludeResult.malloc(stack)
                        .source_name(stack.ASCII(s))
                        .content(stack.bytes(fileInputStream.readAllBytes()))
                        .user_data(user_data).address();
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }

        }
    }
    //TODO: Don't actually need the Releaser at all, (MemoryStack frees this for us)
    //But ShaderC won't let us create the Includer without a corresponding Releaser, (so we need it anyway)
    private static class ShaderReleaser implements ShadercIncludeResultReleaseI {
        @Override
        public void invoke(long user_data, long include_result) {
            //TODO:MAybe dump Shader Compiled Binaries here to a .Misc Diretcory to allow easy caching.recompilation...
        }
    }

}