import test from 'node:test';
import assert from 'node:assert/strict';
import { backendCommands, userCommands } from '../src/backend-bot.mjs';
import { rngPresentation } from '../../shared/rng-presentation.mjs';
import { deliverRngPublication } from '../../shared/rng-delivery.mjs';

test('RNG tester is additive Backend-only and offers no production channel/configuration', () => {
  const command=backendCommands.find(command=>command.name==='rng-test');assert.ok(command);
  assert.equal(command.default_member_permissions,'0');assert.deepEqual(command.contexts,[0]);
  assert.ok(!userCommands.some(command=>command.name==='rng-test'));
  assert.deepEqual(command.options.map(option=>option.name),['dry-run','mock-delivery']);
  for(const option of command.options){
    assert.deepEqual(option.options.map(field=>field.name),['fixture','input']);
    assert.equal(option.options[1].max_length,2000);assert.equal(option.options[0].required,false);
    assert.deepEqual(option.options[0].choices.map(choice=>choice.value),['handle','warden-heart','duplicate','malformed']);
  }
});

test('the shared real publication helper safely rehearses a lost ACK with a TEST-only closed mock',async()=>{
  const now=Date.now(),event={id:'a'.repeat(32),item:'NECRON_HANDLE',activity:'dungeon',occurredAt:now,player:'Anonymous',unverified:true,
    rarity:'LEGENDARY',displayName:"Necron's Handle",floor:'F7'};
  const receipts=new Map(),cards=[];let acks=0;
  const hooks={channelId:'11111111111111111',now:()=>now,present:(event,time)=>rngPresentation(event,time,true),
    receipts:{get:id=>receipts.get(id),async record(id,message){receipts.set(id,message)}},
    async send(card){cards.push(card);return{id:'22222222222222222'}},async ack(raw){acks++;assert.equal(raw.messageId,'22222222222222222');if(acks===1)throw Error('mock ACK unavailable');return{}},
    async remove(){assert.fail('No deletion expected')}};
  const delivery={id:event.id,event,channelId:hooks.channelId,operation:'publish',claim:'c'.repeat(43)};
  await assert.rejects(deliverRngPublication(delivery,hooks),/mock ACK unavailable/);
  await deliverRngPublication({...delivery,claim:'d'.repeat(43)},hooks);
  assert.equal(cards.length,1);assert.equal(acks,2);assert.match(cards[0].embeds[0].title,/^TEST ·/);
  assert.match(cards[0].embeds[0].footer.text,/TEST/);assert.deepEqual(cards[0].allowedMentions.parse,[]);
  assert.deepEqual(cards[0].embeds[0].fields.filter(field=>field.name.startsWith('Observed')).map(field=>field.value),['legendary','F7']);
  const normal=rngPresentation(event,now);assert.ok(!normal.embeds[0].title.startsWith('TEST'));assert.match(normal.embeds[0].description,/Anonymous/);
  for(const bad of[{...delivery,channelId:'99999999999999999'},{...delivery,claim:'invalid'},{...delivery,event:{...event,id:'b'.repeat(32)}},{...delivery,event:{...event,floor:'M8'}}])
    await assert.rejects(deliverRngPublication(bad,hooks),/Invalid RNG publication/);
  assert.equal(cards.length,1,'Invalid mock inputs cannot publish a card');
});
