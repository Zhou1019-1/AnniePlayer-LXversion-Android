"""生成 WrapperLite 启动图标（红色圆角方块 + 白色音符）。

设计来源：`app/src/main/java/com/annieplayer/wrapperlite/ui/Screens.kt` 里的
`AppleMusicMark()` —— 打开页面顶部那个红色音乐图标。这里按同一套几何参数
（音符框 = 方块边长的 0.52、描边 0.12、椭圆音符头、二次贝塞尔符尾）重绘成位图，
供 API 24/25 的 legacy 图标使用；自适应图标（API 26+）用等价的 VectorDrawable。

注意：所有笔画都用**填充多边形**画（不用 `ImageDraw.line(width=...)`）。Pillow 的
粗线是逐段矩形+斜接，沿着弯曲的符尾会出现锯齿状“梳齿”，必须先按法线偏移出带状
多边形再填充。
"""

from PIL import Image, ImageDraw
import math
import os

RED = (0xFA, 0x23, 0x3B, 255)
WHITE = (255, 255, 255, 255)
SS = 8  # 超采样倍数

OUT_ROOT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "app", "src", "main", "res",
)

# mdpi..xxxhdpi 的启动图标边长
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def _bezier(t: float, p0, p1, p2):
    inv = 1 - t
    return (
        inv * inv * p0[0] + 2 * inv * t * p1[0] + t * t * p2[0],
        inv * inv * p0[1] + 2 * inv * t * p1[1] + t * t * p2[1],
    )


def _band(p0, p1, p2, half: float, steps: int = 96):
    """把二次贝塞尔偏移成一条带宽 2*half 的带状多边形。"""
    left, right = [], []
    for i in range(steps + 1):
        t = i / steps
        cur = _bezier(t, p0, p1, p2)
        nxt = _bezier(min(1.0, t + 1e-3), p0, p1, p2)
        prv = _bezier(max(0.0, t - 1e-3), p0, p1, p2)
        dx, dy = nxt[0] - prv[0], nxt[1] - prv[1]
        length = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / length, dx / length
        left.append((cur[0] + nx * half, cur[1] + ny * half))
        right.append((cur[0] - nx * half, cur[1] - ny * half))
    return left + right[::-1]


def draw_note(d: ImageDraw.ImageDraw, ox: float, oy: float, side: float) -> None:
    """在 (ox, oy) 起、边长 side 的方框里画音符（坐标与 Compose 版一一对应）。"""
    stroke = side * 0.12
    half = stroke / 2

    def px(fx: float) -> float:
        return ox + fx * side

    def py(fy: float) -> float:
        return oy + fy * side

    def dot(x: float, y: float) -> None:
        d.ellipse([x - half, y - half, x + half, y + half], fill=WHITE)

    # 符干：竖条 + 两端圆头
    stem_top = (px(0.62), py(0.08))
    stem_bottom = (px(0.62), py(0.68))
    d.rectangle(
        [stem_top[0] - half, stem_top[1], stem_top[0] + half, stem_bottom[1]],
        fill=WHITE,
    )
    dot(*stem_top)
    dot(*stem_bottom)

    # 符尾：二次贝塞尔（与 Compose 的 quadraticBezierTo 同控制点）
    p0 = stem_top
    p1 = (px(0.92), py(0.20))
    p2 = (px(0.72), py(0.42))
    d.polygon(_band(p0, p1, p2, half), fill=WHITE)
    dot(*p2)

    # 符头：椭圆
    d.ellipse(
        [px(0.18), py(0.58), px(0.18) + side * 0.46, py(0.58) + side * 0.34],
        fill=WHITE,
    )


def render_legacy(size: int) -> Image.Image:
    """legacy 图标：整张图就是那个红色圆角方块，音符占 0.52。"""
    big = size * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, big - 1, big - 1], radius=big * 0.24, fill=RED)
    side = big * 0.52
    draw_note(d, (big - side) / 2, (big - side) / 2, side)
    return img.resize((size, size), Image.LANCZOS)


def main() -> None:
    for density, size in DENSITIES.items():
        folder = os.path.normpath(os.path.join(OUT_ROOT, f"mipmap-{density}"))
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, "ic_launcher.png")
        render_legacy(size).save(path)
        print(f"{density:8s} {size:4d}px  ->  {path}")


if __name__ == "__main__":
    main()
