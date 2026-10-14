(() => {
  const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const narrow = innerWidth < 640;

  const fill = (host, count) => {
    for (let i = 0; i < count; i++) {
      const s = document.createElement("span");
      s.className = "strand";
      s.style.left = ((i + Math.random() * 0.8) / count * 100).toFixed(2) + "%";
      s.style.opacity = (0.15 + Math.random() * 0.4).toFixed(2);
      s.style.setProperty("--sway", (8 + Math.random() * 6).toFixed(1) + "s");
      s.style.setProperty("--fall", (6 + Math.random() * 8).toFixed(1) + "s");
      s.style.setProperty("--fall-delay", (-Math.random() * 12).toFixed(1) + "s");
      host.appendChild(s);
    }
  };
  document.querySelectorAll(".strands").forEach((host, i) => fill(host, (narrow ? 16 : 40) >> i));

  if (still) return;
  const puppet = document.getElementById("puppet");
  const strands = document.querySelector(".hero .strands");
  let mx = 0, my = 0, cx = 0, cy = 0, sy = scrollY;
  addEventListener("pointermove", e => {
    mx = e.clientX / innerWidth - 0.5;
    my = e.clientY / innerHeight - 0.5;
  }, { passive: true });
  const tick = t => {
    cx += (mx - cx) * 0.05;
    cy += (my - cy) * 0.05;
    sy += (scrollY - sy) * 0.12;
    if (sy < innerHeight * 1.2) {
      const s = t / 1000;
      puppet.style.transform =
        `translate3d(${cx * -16}px, ${sy * 0.22 + cy * -10 + Math.sin(s * 0.8) * 6}px, 0) ` +
        `rotateX(${10 + cy * -4}deg) rotateY(${-24 + cx * 7}deg) rotateZ(${7 + Math.sin(s * 0.6) * 0.8}deg)`;
      strands.style.transform = `translate3d(${cx * 8}px, ${sy * 0.08}px, 0)`;
    }
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
})();
