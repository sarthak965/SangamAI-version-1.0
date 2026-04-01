import { useEffect, useState } from "react";

const STORAGE_KEY = "sangam-theme";
type Theme = "light" | "dark";

function applyTheme(theme: Theme) {
  document.documentElement.setAttribute("data-theme", theme);
}

export function useTheme() {
  const [theme, setTheme] = useState<Theme>("dark");

  useEffect(() => {
    applyTheme("dark");
    try {
      localStorage.setItem(STORAGE_KEY, "dark");
    } catch {
      /* ignore */
    }
    if (theme !== "dark") {
      setTheme("dark");
    }
  }, [theme]);

  const toggle = () => {};

  return { theme, toggle, setTheme } as const;
}
