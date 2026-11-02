import { DM_Mono, DM_Sans } from "next/font/google";
import "./ship.css";

const sans = DM_Sans({ subsets: ["latin"], variable: "--font-dm-sans" });
const mono = DM_Mono({ subsets: ["latin"], weight: ["400", "500"], variable: "--font-dm-mono" });

/** Type and component styles from the UI design (A · New Delivery, B · Compare Plans). */
export default function ShipLayout({ children }: { children: React.ReactNode }) {
  return <div className={`ship ${sans.variable} ${mono.variable}`}>{children}</div>;
}
