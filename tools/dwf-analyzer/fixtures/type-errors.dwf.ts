/* zcode-workflow
description: 故意写坏的脚本
*/
const a = agent();

export default async function () {
  const n: number = "not a number";
  await a.ask({});
  return undeclaredThing;
}
