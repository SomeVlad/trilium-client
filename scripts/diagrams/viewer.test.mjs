import {test} from 'node:test';
import assert from 'node:assert/strict';
import {safeImageData,prepareScene,clampScale} from './viewer-policy.mjs';
test('embedded raster data accepted and active or remote image content refused',()=>{
 assert.equal(safeImageData('data:image/png;base64,AAAA'),true);
 for(const value of ['https://example.org/private.png','file:///data/private.png','data:image/svg+xml;base64,PHN2Zz4=',null]) assert.equal(safeImageData(value),false);
});
test('canvas data preserves geometry while removing links, embeddables and remote images',()=>{
 const image='data:image/png;base64,AAAA';
 const scene=prepareScene(JSON.stringify({elements:[
  {id:'text',type:'text',x:1,y:2,width:10,height:20,text:'Synthetic',link:'javascript:alert(1)'},
  {id:'embedded',type:'embeddable',x:0,y:0,width:100,height:100,link:'https://example.org/'},
  {id:'img',type:'image',x:0,y:0,width:10,height:10,fileId:'remote'},
  {id:'deleted',type:'rectangle',isDeleted:true,x:0,y:0,width:10,height:10}],
 files:{safe:{dataURL:image},remote:{dataURL:'https://example.org/private'}}}));
 assert.deepEqual(scene.elements.map(e=>e.id),['text','img']);
 assert.equal(scene.elements[0].text,'Synthetic');assert.equal(scene.elements[0].link,null);
 assert.equal(scene.elements[1].status,'error');assert.deepEqual(Object.keys(scene.files),['safe']);
});
test('invalid scene shape and extreme coordinates fail safely',()=>{
 assert.throws(()=>prepareScene('{broken'));
 assert.throws(()=>prepareScene('{"elements":{}}'));
 const scene=prepareScene(JSON.stringify({elements:[{type:'text',x:1e20,y:0,width:1,height:1}]}));assert.deepEqual(scene.elements,[]);
});
test('zoom bounds remain useful and finite at ordinary extremes',()=>{
 assert.equal(clampScale(0.00001),0.02);assert.equal(clampScale(999),16);assert.equal(clampScale(1.25),1.25);
});
