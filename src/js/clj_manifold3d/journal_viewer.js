import * as THREE from 'three';
import {OrbitControls} from 'three/examples/jsm/controls/OrbitControls.js';
import {GLTFLoader} from 'three/examples/jsm/loaders/GLTFLoader.js';

// One renderer per visible result. Offscreen viewers release their WebGL
// context, allowing long journals to contain more than the browser's limit.
export function createViewer(element, url) {
  let renderer, scene, camera, controls, root, mixer, frame, disposed=false, generation=0, visible=false;
  let paused=false, wire=false, meshCount=0, clips=0;
  const clock=new THREE.Clock();
  const loader=new GLTFLoader();
  const render=()=>{if(!renderer||!visible)return;frame=requestAnimationFrame(render);if(!paused)mixer?.update(Math.min(clock.getDelta(),.1));else clock.getDelta();controls.update();renderer.render(scene,camera);};
  function releaseRoot(object){object?.traverse(o=>{o.geometry?.dispose();for(const m of (Array.isArray(o.material)?o.material:[o.material])){if(m){for(const v of Object.values(m))if(v?.isTexture)v.dispose();m.dispose();}}});}
  function suspend(){generation++;cancelAnimationFrame(frame);mixer?.stopAllAction();controls?.dispose();releaseRoot(root);root=null;mixer=null;renderer?.dispose();renderer?.forceContextLoss();renderer?.domElement.remove();renderer=null;}
  const resize=()=>{if(!renderer)return;const w=Math.max(element.clientWidth,1),h=Math.max(element.clientHeight,1);renderer.setSize(w,h,false);camera.aspect=w/h;camera.updateProjectionMatrix();};
  function fit(){if(!root)return;const b=new THREE.Box3().setFromObject(root),center=b.getCenter(new THREE.Vector3());const radius=Math.max(b.getSize(new THREE.Vector3()).length()/2,.01);camera.position.copy(center).add(new THREE.Vector3(1.1,-1.5,1.1).normalize().multiplyScalar(radius*3));camera.near=radius/1000;camera.far=radius*100;controls.target.copy(center);camera.updateProjectionMatrix();controls.update();}
  async function resume(){
    if(renderer||disposed)return;
    const current=++generation;
    renderer=new THREE.WebGLRenderer({antialias:true,alpha:true});renderer.setPixelRatio(Math.min(devicePixelRatio,2));renderer.outputColorSpace=THREE.SRGBColorSpace;renderer.setClearColor(0xf4f6f3,1);element.appendChild(renderer.domElement);
    scene=new THREE.Scene();camera=new THREE.PerspectiveCamera(35,1,.01,1000);camera.up.set(0,0,1);controls=new OrbitControls(camera,renderer.domElement);controls.enableDamping=true;
    scene.add(new THREE.HemisphereLight(0xffffff,0x658078,2));const light=new THREE.DirectionalLight(0xffffff,2);light.position.set(30,-40,50);scene.add(light);resize();render();
    try{const gltf=await loader.loadAsync(url);if(disposed||current!==generation){releaseRoot(gltf.scene);return;}root=gltf.scene;root.updateMatrixWorld(true);scene.add(root);meshCount=0;root.traverse(o=>{if(o.isMesh){meshCount++;for(const m of(Array.isArray(o.material)?o.material:[o.material]))m.wireframe=wire;}});clips=gltf.animations.length;mixer=clips?new THREE.AnimationMixer(root):null;gltf.animations.forEach(c=>mixer.clipAction(c).play());fit();element.dataset.loaded='true';element.dataset.meshes=String(meshCount);element.dataset.animations=String(clips);}
    catch(e){element.dataset.error=e.message;element.dispatchEvent(new CustomEvent('viewer-error',{detail:e.message,bubbles:true}));}
  }
  const resizeObserver=new ResizeObserver(resize);resizeObserver.observe(element);
  const observer=new IntersectionObserver(entries=>{visible=entries[0].isIntersecting;if(visible)resume();else suspend();},{rootMargin:'100px'});observer.observe(element);
  return {fit,setWireframe(value){wire=value;root?.traverse(o=>{if(o.isMesh)for(const m of(Array.isArray(o.material)?o.material:[o.material]))m.wireframe=wire;});},setPaused(value){paused=value;},
    inspect(){return {meshCount,clips,paused,wire,positions:root?root.children.map(o=>o.matrixWorld.elements.slice()):[]};},
    dispose(){disposed=true;observer.disconnect();resizeObserver.disconnect();suspend();}};
}

export function sectionSvg(element, polygons){
  const points=polygons.flat();if(!points.length){element.textContent='Empty cross-section';return;}
  const xs=points.map(p=>p[0]),ys=points.map(p=>p[1]);const minX=Math.min(...xs),maxX=Math.max(...xs),minY=Math.min(...ys),maxY=Math.max(...ys),pad=Math.max(maxX-minX,maxY-minY,.01)*.08;
  const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg'),path=document.createElementNS(ns,'path');
  svg.setAttribute('viewBox',`${minX-pad} ${-maxY-pad} ${maxX-minX+2*pad} ${maxY-minY+2*pad}`);svg.setAttribute('role','img');svg.setAttribute('aria-label','Cross-section');
  path.setAttribute('d',polygons.map(p=>p.map(([x,y],i)=>`${i?'L':'M'}${x} ${-y}`).join(' ')+' Z').join(' '));path.setAttribute('fill-rule','evenodd');path.setAttribute('fill','#498c86');svg.appendChild(path);element.replaceChildren(svg);
}
