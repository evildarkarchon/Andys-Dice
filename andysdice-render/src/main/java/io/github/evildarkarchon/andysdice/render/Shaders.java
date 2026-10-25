package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL20C.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20C.glAttachShader;
import static org.lwjgl.opengl.GL20C.glCompileShader;
import static org.lwjgl.opengl.GL20C.glCreateProgram;
import static org.lwjgl.opengl.GL20C.glCreateShader;
import static org.lwjgl.opengl.GL20C.glDeleteProgram;
import static org.lwjgl.opengl.GL20C.glDeleteShader;
import static org.lwjgl.opengl.GL20C.glDetachShader;
import static org.lwjgl.opengl.GL20C.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20C.glGetProgrami;
import static org.lwjgl.opengl.GL20C.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL20C.glGetShaderi;
import static org.lwjgl.opengl.GL20C.glLinkProgram;
import static org.lwjgl.opengl.GL20C.glShaderSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Loads GLSL from this module's {@code shaders/} resources and builds programs from it. GLSL 3.30 has no
 * {@code #include}, so a line {@code #include "file"} is replaced by that resource's text, recursively, as the
 * prototypes' loader did (render.md).
 *
 * <p>{@link #source} touches no GL. {@link #compile} and {@link #program} run on the render thread only.
 */
final class Shaders {

    private static final String DIRECTORY = "shaders/";
    private static final String INCLUDE = "#include \"";

    private Shaders() {}

    /**
     * Reads a shader resource with its includes spliced in.
     *
     * @param name the resource's name under {@code shaders/}, such as {@code "flat.vert"}
     * @return the full source
     * @throws IllegalArgumentException if the resource or an included one doesn't exist, or includes form a
     *     cycle
     * @throws UncheckedIOException if a resource can't be read
     */
    static String source(String name) {
        return source(name, new ArrayDeque<>());
    }

    /**
     * Compiles one shader.
     *
     * @param type {@code GL_VERTEX_SHADER} or {@code GL_FRAGMENT_SHADER}
     * @param source the GLSL source
     * @param name the name to report in a failure
     * @return the shader object
     * @throws IllegalStateException if it doesn't compile; the message holds the name and the driver's log
     */
    static int compile(int type, String source, String name) {
        RenderThread.checkRenderThread();
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException(name + " didn't compile: " + log.strip());
        }
        return shader;
    }

    /**
     * Compiles and links a program from two shader resources.
     *
     * @param vertex the vertex shader's resource name
     * @param fragment the fragment shader's resource name
     * @return the linked program
     * @throws IllegalStateException if either shader doesn't compile or the program doesn't link
     */
    static int program(String vertex, String fragment) {
        int vs = compile(GL_VERTEX_SHADER, source(vertex), vertex);
        int fs;
        try {
            fs = compile(GL_FRAGMENT_SHADER, source(fragment), fragment);
        } catch (RuntimeException e) {
            glDeleteShader(vs);
            throw e;
        }
        int program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        // The program keeps what it linked; the shader objects aren't needed any more.
        glDetachShader(program, vs);
        glDetachShader(program, fs);
        glDeleteShader(vs);
        glDeleteShader(fs);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException(vertex + " + " + fragment + " didn't link: " + log.strip());
        }
        return program;
    }

    private static String source(String name, Deque<String> including) {
        if (including.contains(name)) {
            // The deque is a stack, newest first; reversed, it reads in include order.
            throw new IllegalArgumentException("shader include cycle: "
                    + String.join(" -> ", including.reversed()) + " -> " + name);
        }
        String text;
        try (InputStream in = Shaders.class.getResourceAsStream(DIRECTORY + name)) {
            if (in == null) {
                throw new IllegalArgumentException("no shader resource " + DIRECTORY + name);
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        including.push(name);
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\\R", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith(INCLUDE) && trimmed.endsWith("\"")) {
                out.append(source(trimmed.substring(INCLUDE.length(), trimmed.length() - 1), including));
            } else {
                out.append(line);
            }
            out.append('\n');
        }
        including.pop();
        return out.toString();
    }
}
