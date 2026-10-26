import { useEffect, useRef } from "react";
import {
  EXHIBIT_CELL_COUNT,
  EXHIBIT_GRID_SIZE,
  exhibitRevealRanks,
  revealedCellsForBasisPoints,
} from "./exhibits";

type Renderer = {
  render(
    count: number,
    highlightStart?: number,
    highlightEnd?: number,
    highlightAlpha?: number,
  ): void;
  dispose(): void;
};

type ExhibitRevealProps = Readonly<{
  basisPoints: number;
  imagePath: string;
  label: string;
  reduceMotion: boolean;
}>;

export function ExhibitReveal({ basisPoints, imagePath, label, reduceMotion }: ExhibitRevealProps) {
  const webglCanvas = useRef<HTMLCanvasElement | null>(null);
  const fallbackCanvas = useRef<HTMLCanvasElement | null>(null);
  const renderer = useRef<Renderer | null>(null);
  const renderedCells = useRef(revealedCellsForBasisPoints(basisPoints));
  const animationFrame = useRef<number | null>(null);

  useEffect(() => {
    const webgl = webglCanvas.current;
    const fallback = fallbackCanvas.current;
    if (!webgl || !fallback) return;
    let cancelled = false;
    const image = new Image();
    const onContextLost = (event: Event) => {
      event.preventDefault();
      if (cancelled || !image.complete) return;
      renderer.current?.dispose();
      webgl.hidden = true;
      fallback.hidden = false;
      renderer.current = createCanvasRenderer(fallback, image);
      renderer.current.render(renderedCells.current);
    };
    webgl.addEventListener("webglcontextlost", onContextLost);
    image.decoding = "async";
    image.onload = () => {
      if (cancelled) return;
      renderer.current?.dispose();
      renderer.current = createRenderer(webgl, fallback, image);
      renderer.current.render(renderedCells.current);
    };
    image.onerror = () => {
      if (cancelled) return;
      renderer.current?.dispose();
      renderer.current = createCoverRenderer(webgl, fallback);
      renderer.current.render(renderedCells.current);
    };
    image.src = imagePath;
    return () => {
      cancelled = true;
      webgl.removeEventListener("webglcontextlost", onContextLost);
      if (animationFrame.current !== null) cancelAnimationFrame(animationFrame.current);
      animationFrame.current = null;
      renderer.current?.dispose();
      renderer.current = null;
    };
  }, [imagePath]);

  useEffect(() => {
    const active = renderer.current;
    if (!active) {
      renderedCells.current = revealedCellsForBasisPoints(basisPoints);
      return;
    }
    const from = renderedCells.current;
    const to = revealedCellsForBasisPoints(basisPoints);
    if (animationFrame.current !== null) cancelAnimationFrame(animationFrame.current);
    if (reduceMotion || from === to || to < from) {
      renderedCells.current = to;
      active.render(to);
      return;
    }
    const startedAt = performance.now();
    const duration = 700;
    const draw = (now: number) => {
      const elapsed = Math.min(1, (now - startedAt) / duration);
      const eased = 1 - (1 - elapsed) ** 3;
      const current = Math.round(from + (to - from) * eased);
      active.render(current, from, to, 1 - elapsed);
      if (elapsed < 1) {
        animationFrame.current = requestAnimationFrame(draw);
      } else {
        animationFrame.current = null;
        renderedCells.current = to;
        active.render(to);
      }
    };
    animationFrame.current = requestAnimationFrame(draw);
    return () => {
      if (animationFrame.current !== null) cancelAnimationFrame(animationFrame.current);
      animationFrame.current = null;
    };
  }, [basisPoints, reduceMotion]);

  return (
    <div className="exhibit-reveal" role="img" aria-label={label}>
      <canvas ref={webglCanvas} width="960" height="960" />
      <canvas ref={fallbackCanvas} width="960" height="960" hidden />
    </div>
  );
}

function createRenderer(
  webglCanvas: HTMLCanvasElement,
  fallbackCanvas: HTMLCanvasElement,
  image: HTMLImageElement,
): Renderer {
  try {
    const renderer = createWebGlRenderer(webglCanvas, image);
    if (renderer) {
      webglCanvas.hidden = false;
      fallbackCanvas.hidden = true;
      return renderer;
    }
  } catch {
    // Canvas 2D retains the same deterministic mask when WebGL is unavailable or loses setup.
  }
  webglCanvas.hidden = true;
  fallbackCanvas.hidden = false;
  return createCanvasRenderer(fallbackCanvas, image);
}

function createCoverRenderer(
  webglCanvas: HTMLCanvasElement,
  fallbackCanvas: HTMLCanvasElement,
): Renderer {
  webglCanvas.hidden = true;
  fallbackCanvas.hidden = false;
  const context = fallbackCanvas.getContext("2d");
  return {
    render() {
      if (!context) return;
      drawCover(context, fallbackCanvas.width, fallbackCanvas.height);
    },
    dispose() {},
  };
}

function createWebGlRenderer(canvas: HTMLCanvasElement, image: HTMLImageElement): Renderer | null {
  const gl = canvas.getContext("webgl2", {
    alpha: false,
    antialias: false,
    preserveDrawingBuffer: true,
  });
  if (!gl) return null;
  const vertex = `#version 300 es
precision highp float;
out vec2 uv;
const vec2 points[3]=vec2[3](vec2(-1.,-1.),vec2(3.,-1.),vec2(-1.,3.));
void main(){gl_Position=vec4(points[gl_VertexID],0.,1.);uv=(gl_Position.xy+1.)*.5;}`;
  const fragment = `#version 300 es
precision highp float;
in vec2 uv;
out vec4 outColor;
uniform sampler2D artwork;
uniform sampler2D ranks;
uniform float count;
uniform float highlightStart;
uniform float highlightEnd;
uniform float highlightAlpha;
float hash(vec2 point){return fract(sin(dot(point,vec2(127.1,311.7)))*43758.5453);}
void main(){
  vec2 samplePoint=vec2(uv.x,1.0-uv.y);
  vec3 bytes=floor(texture(ranks,samplePoint).rgb*255.0+.5);
  float rank=bytes.r+bytes.g*256.0+bytes.b*65536.0;
  vec3 art=texture(artwork,samplePoint).rgb;
  float grain=hash(floor(samplePoint*960.0));
  vec3 cover=mix(vec3(.105,.073,.045),vec3(.285,.205,.132),.28+grain*.42);
  cover*=.82+.18*sin(samplePoint.y*48.0+grain*4.0);
  bool shown=rank<count;
  vec3 colour=shown?art:cover;
  if(shown&&rank>=highlightStart&&rank<highlightEnd){
    colour=mix(colour,vec3(.95,.72,.36),highlightAlpha);
  }
  outColor=vec4(colour,1.0);
}`;
  const compile = (type: number, source: string) => {
    const shader = gl.createShader(type);
    if (!shader) throw new Error("exhibit_shader_unavailable");
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      throw new Error("exhibit_shader_invalid");
    }
    return shader;
  };
  const program = gl.createProgram();
  if (!program) throw new Error("exhibit_program_unavailable");
  gl.attachShader(program, compile(gl.VERTEX_SHADER, vertex));
  gl.attachShader(program, compile(gl.FRAGMENT_SHADER, fragment));
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    throw new Error("exhibit_program_invalid");
  }
  const activateProgram = gl.useProgram.bind(gl);
  activateProgram(program);
  const vertexArray = gl.createVertexArray();
  if (!vertexArray) throw new Error("exhibit_vertex_array_unavailable");
  gl.bindVertexArray(vertexArray);

  const artwork = gl.createTexture();
  gl.activeTexture(gl.TEXTURE0);
  gl.bindTexture(gl.TEXTURE_2D, artwork);
  configureTexture(gl, gl.LINEAR);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, image);

  const rankBytes = new Uint8Array(EXHIBIT_CELL_COUNT * 4);
  exhibitRevealRanks().forEach((rank, index) => {
    rankBytes[index * 4] = rank & 255;
    rankBytes[index * 4 + 1] = (rank >>> 8) & 255;
    rankBytes[index * 4 + 2] = (rank >>> 16) & 255;
    rankBytes[index * 4 + 3] = 255;
  });
  const rankTexture = gl.createTexture();
  gl.activeTexture(gl.TEXTURE1);
  gl.bindTexture(gl.TEXTURE_2D, rankTexture);
  configureTexture(gl, gl.NEAREST);
  gl.texImage2D(
    gl.TEXTURE_2D,
    0,
    gl.RGBA,
    EXHIBIT_GRID_SIZE,
    EXHIBIT_GRID_SIZE,
    0,
    gl.RGBA,
    gl.UNSIGNED_BYTE,
    rankBytes,
  );

  gl.uniform1i(gl.getUniformLocation(program, "artwork"), 0);
  gl.uniform1i(gl.getUniformLocation(program, "ranks"), 1);
  const countLocation = gl.getUniformLocation(program, "count");
  const startLocation = gl.getUniformLocation(program, "highlightStart");
  const endLocation = gl.getUniformLocation(program, "highlightEnd");
  const alphaLocation = gl.getUniformLocation(program, "highlightAlpha");
  gl.viewport(0, 0, canvas.width, canvas.height);

  return {
    render(count, highlightStart = -1, highlightEnd = -1, highlightAlpha = 0) {
      gl.uniform1f(countLocation, count);
      gl.uniform1f(startLocation, highlightStart);
      gl.uniform1f(endLocation, highlightEnd);
      gl.uniform1f(alphaLocation, highlightAlpha);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    },
    dispose() {
      gl.deleteTexture(artwork);
      gl.deleteTexture(rankTexture);
      gl.deleteVertexArray(vertexArray);
      gl.deleteProgram(program);
    },
  };
}

function configureTexture(gl: WebGL2RenderingContext, filter: number) {
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filter);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filter);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
}

function createCanvasRenderer(canvas: HTMLCanvasElement, image: HTMLImageElement): Renderer {
  const context = canvas.getContext("2d");
  const mask = document.createElement("canvas");
  mask.width = EXHIBIT_GRID_SIZE;
  mask.height = EXHIBIT_GRID_SIZE;
  const maskContext = mask.getContext("2d");
  const composition = document.createElement("canvas");
  composition.width = canvas.width;
  composition.height = canvas.height;
  const compositionContext = composition.getContext("2d");
  const ranks = exhibitRevealRanks();

  return {
    render(count) {
      if (!context || !maskContext || !compositionContext) return;
      const pixels = maskContext.createImageData(EXHIBIT_GRID_SIZE, EXHIBIT_GRID_SIZE);
      for (let index = 0; index < EXHIBIT_CELL_COUNT; index += 1) {
        pixels.data[index * 4 + 3] = ranks[index] < count ? 255 : 0;
      }
      maskContext.putImageData(pixels, 0, 0);
      drawCover(context, canvas.width, canvas.height);
      compositionContext.clearRect(0, 0, canvas.width, canvas.height);
      compositionContext.globalCompositeOperation = "source-over";
      compositionContext.drawImage(image, 0, 0, canvas.width, canvas.height);
      compositionContext.globalCompositeOperation = "destination-in";
      compositionContext.imageSmoothingEnabled = false;
      compositionContext.drawImage(mask, 0, 0, canvas.width, canvas.height);
      compositionContext.globalCompositeOperation = "source-over";
      context.drawImage(composition, 0, 0);
    },
    dispose() {},
  };
}

function drawCover(context: CanvasRenderingContext2D, width: number, height: number) {
  const cover = context.createLinearGradient(0, 0, width, height);
  cover.addColorStop(0, "#4b3827");
  cover.addColorStop(1, "#17100b");
  context.fillStyle = cover;
  context.fillRect(0, 0, width, height);
}
