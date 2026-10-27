import { redirect } from "next/navigation";

// After login the user lands on New Delivery (Demo step 1).
export default function Home() {
  redirect("/ship");
}
