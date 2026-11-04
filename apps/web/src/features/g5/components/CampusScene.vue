<!-- Presentation adapted from the G6 bootstrap's window, desk and plant illustration. -->
<template>
  <div class="campus-scene" aria-hidden="true">
    <div class="scene-sun"></div>
    <div class="scene-window"><i></i><i></i><i></i><i></i></div>
    <div class="scene-desk"></div>
    <div class="scene-book scene-book-one"></div>
    <div class="scene-book scene-book-two"></div>
    <div class="scene-plant"><span></span><i></i><b></b></div>
    <div class="scene-chair"></div>
    <p>留一处空间，给新的想法。</p>
  </div>
</template>

<style scoped>
.campus-scene{position:relative;isolation:isolate;width:100%;aspect-ratio:1.22;border-radius:160px 160px 24px 24px;background:#e3e9d9;overflow:hidden}
.scene-sun{position:absolute;width:34%;aspect-ratio:1;border-radius:50%;background:#eed39a;right:-5%;top:15%}
.scene-window{position:absolute;left:17%;top:10%;width:43%;height:49%;display:grid;grid-template-columns:1fr 1fr;gap:7px;border:8px solid #f5f4df;border-radius:90px 90px 0 0;overflow:hidden;transform:skewY(-7deg);z-index:1}
.scene-window i{background:#b4cbb7}.scene-window i:nth-child(2){background:#d7ddba}.scene-window i:nth-child(3){background:#c9d8bc}.scene-window i:nth-child(4){background:#becfb1}
.scene-desk{position:absolute;top:63%;left:8%;right:5%;height:13px;background:#9d7853;transform:skewY(-5deg);z-index:3}
.scene-desk::before,.scene-desk::after{content:"";position:absolute;top:12px;width:10px;height:110px;background:#b8956e}.scene-desk::before{left:6%}.scene-desk::after{right:6%}
.scene-book{position:absolute;left:22%;width:21%;height:8px;z-index:4;transform:rotate(-5deg);border-radius:2px}.scene-book-one{top:57.5%;background:#57735a}.scene-book-two{top:60.5%;width:24%;background:#e5c289}
.scene-chair{position:absolute;width:25%;height:26%;top:67%;left:42%;border-radius:28px 28px 8px 8px;background:#637d61;z-index:4;transform:rotate(-4deg)}
.scene-chair::before,.scene-chair::after{content:"";position:absolute;bottom:-25px;width:7px;height:35px;background:#344d3f}.scene-chair::before{left:15%}.scene-chair::after{right:15%}
.scene-plant{position:absolute;right:14%;top:48%;z-index:4}.scene-plant span{display:block;width:28px;height:36px;background:#bc7f57;border-radius:3px 3px 11px 11px}.scene-plant i,.scene-plant b{position:absolute;width:20px;height:40px;border-radius:90% 0 90% 0;background:#6d8a54;top:-32px;left:3px;transform:rotate(-32deg)}.scene-plant b{top:-41px;left:14px;transform:rotate(20deg);background:#4b7454}
.campus-scene>p{position:absolute;bottom:12px;right:16px;color:#64755e;font-size:10px;letter-spacing:.1em;z-index:5;background:#f5f4dfd9;padding:3px 7px;border-radius:5px}
</style>
