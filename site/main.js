(() => {
  const root = document.documentElement;
  const system = matchMedia("(prefers-color-scheme: dark)");
  const toggle = document.querySelector(".theme-toggle");
  const themeColor = document.querySelector('meta[name="theme-color"]');
  const stored = () => { try { return localStorage.getItem("theme"); } catch (e) { return null; } };
  const store = value => { try { value ? localStorage.setItem("theme", value) : localStorage.removeItem("theme"); } catch (e) {} };
  const apply = theme => {
    root.dataset.theme = theme;
    const next = theme === "dark" ? "light" : "dark";
    toggle.setAttribute("aria-label", `Switch to ${next} theme`);
    toggle.title = `Switch to ${next} theme`;
    themeColor.content = theme === "dark" ? "#0b0c0e" : "#faf6f0";
  };
  apply(root.dataset.theme || (system.matches ? "dark" : "light"));
  toggle.addEventListener("click", () => {
    const next = root.dataset.theme === "dark" ? "light" : "dark";
    store(next === (system.matches ? "dark" : "light") ? null : next);
    apply(next);
  });
  system.addEventListener("change", e => { if (!stored()) apply(e.matches ? "dark" : "light"); });
})();

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
  const stacked = matchMedia("(max-width: 960px)");
  const tick = t => {
    cx += (mx - cx) * 0.05;
    cy += (my - cy) * 0.05;
    sy += (scrollY - sy) * 0.12;
    if (sy < innerHeight * 1.2) {
      const s = t / 1000;
      puppet.style.transform =
        `translate3d(${cx * -16}px, ${(stacked.matches ? 0 : sy * 0.22) + cy * -10 + Math.sin(s * 0.8) * 6}px, 0) ` +
        `rotateX(${10 + cy * -4}deg) rotateY(${-24 + cx * 7}deg) rotateZ(${7 + Math.sin(s * 0.6) * 0.8}deg)`;
      strands.style.transform = `translate3d(${cx * 8}px, ${sy * 0.08}px, 0)`;
    }
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
})();
