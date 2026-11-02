/* zcode-workflow
description: 三个不同模型的子代理协作
*/
const fast = model('claude-haiku-4');
const researcher = agent('研究员', { system: '你负责检索', model: fast });
const writer = agent('写手', { system: '你负责成文', model: 'claude-sonnet-4' });

log('start');
const notes = await researcher.ask<string>('检索主题');
const draft = await writer.ask<string>(`根据笔记成文：${notes}`);
return draft;
