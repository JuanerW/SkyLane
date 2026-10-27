import { redirect } from "next/navigation";

// The design lands on /ship after login; it is not built yet (Pei), so go to My Orders for now.
// TODO: switch back to "/ship" once app/(app)/ship/page.tsx exists.
export default function Home() {
  redirect("/orders");
}
