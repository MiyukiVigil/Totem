// Run from the repo root: node scripts/android-icons.mjs
// Builds the Android vector icons from desktop/icon.svg's shapes (1024 grid).
import fs from "fs";
const rr = (x, y, w, h, r) =>
  `M${x + r},${y} H${x + w - r} A${r},${r} 0 0 1 ${x + w},${y + r} V${y + h - r} A${r},${r} 0 0 1 ${x + w - r},${y + h} ` +
  `H${x + r} A${r},${r} 0 0 1 ${x},${y + h - r} V${y + r} A${r},${r} 0 0 1 ${x + r},${y} Z`;
const circle = (cx, cy, r) => `M${cx - r},${cy} A${r},${r} 0 1 0 ${cx + r},${cy} A${r},${r} 0 1 0 ${cx - r},${cy} Z`;
// Blocks with their holes (evenodd), so it works as a silhouette too.
const top = rr(332, 150, 360, 200, 48) + circle(440, 250, 34) + circle(584, 250, 34);
const mid = rr(272, 392, 480, 240, 56) + "M473.6,520 A54,54 0 1 1 550.4,520 L560,602 L464,602 Z";
const bot = rr(332, 674, 360, 200, 48) + rr(412, 752, 200, 44, 22);
const art = top + mid + bot;

// Art spans y 150..874 (724). Adaptive icons: 108 grid, 58 tall keeps every corner inside the 66 safe circle.
function vector(size, viewport, scale, color, extra = "") {
  const t = viewport / 2 - 512 * scale;
  return `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated from desktop/icon.svg. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${size}dp" android:height="${size}dp"
    android:viewportWidth="${viewport}" android:viewportHeight="${viewport}">${extra}
    <group android:translateX="${t.toFixed(3)}" android:translateY="${t.toFixed(3)}" android:scaleX="${scale}" android:scaleY="${scale}">
        <path android:fillColor="${color}" android:fillType="evenOdd" android:pathData="${art}" />
    </group>
</vector>
`;
}
const res = "android/app/src/main/res";
fs.mkdirSync(`${res}/drawable`, { recursive: true });
fs.mkdirSync(`${res}/mipmap-anydpi-v26`, { recursive: true });
fs.writeFileSync(`${res}/drawable/ic_launcher_foreground.xml`, vector(108, 108, 58 / 724, "#FBEEE2"));
fs.writeFileSync(`${res}/drawable/ic_launcher_monochrome.xml`, vector(108, 108, 58 / 724, "#FFFFFFFF"));
fs.writeFileSync(`${res}/drawable/ic_stat_totem.xml`, vector(24, 24, 22 / 724, "#FFFFFFFF"));
fs.writeFileSync(`${res}/values/ic_launcher_colors.xml`, `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#B5652B</color>
</resources>
`);
const adaptive = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
`;
fs.writeFileSync(`${res}/mipmap-anydpi-v26/ic_launcher.xml`, adaptive);
fs.writeFileSync(`${res}/mipmap-anydpi-v26/ic_launcher_round.xml`, adaptive);
console.log("icons written");
