import * as THREE from 'three';
import {OrbitControls} from 'three/examples/jsm/controls/OrbitControls.js';
import {GLTFLoader} from 'three/examples/jsm/loaders/GLTFLoader.js';
export {createPresentation} from './journal_presentation.js';

// One renderer per visible result. Offscreen viewers release their WebGL
// context, allowing long journals to contain more than the browser's limit.
export function createViewer(element, url, options = {}) {
  let renderer, scene, camera, controls, root, mixer, frame, disposed=false, generation=0, visible=false;
  let paused=false, wire=false, meshCount=0, clips=0, authoredLights=0, authoredCamera=false;
  let grid, markers, radius=1, measuring=false, points=[], gridVisible=options.grid!==false;
  let savedCamera=options.camera || null, cameraTimer, pointerStart;
  const clock=new THREE.Clock();
  const loader=new GLTFLoader();
  const raycaster=new THREE.Raycaster();
  let frameWindow=element.ownerDocument.defaultView, observer, resizeObserver;
  const cancelFrame=()=>frameWindow.cancelAnimationFrame(frame);
  const render=()=>{if(!renderer||!visible)return;frameWindow=element.ownerDocument.defaultView;frame=frameWindow.requestAnimationFrame(render);if(!paused)mixer?.update(Math.min(clock.getDelta(),.1));else clock.getDelta();controls.update();renderer.render(scene,camera);};
  function releaseRoot(object){object?.traverse(o=>{o.geometry?.dispose();for(const m of (Array.isArray(o.material)?o.material:[o.material])){if(m){for(const v of Object.values(m))if(v?.isTexture)v.dispose();m.dispose();}}});}
  const cameraState=()=>camera&&controls?{position:camera.position.toArray(),target:controls.target.toArray(),near:camera.near,far:camera.far}:savedCamera;
  function saveCamera(){
    clearTimeout(cameraTimer);
    if(!root)return;
    const value=cameraState();
    if(JSON.stringify(value)!==JSON.stringify(savedCamera)){
      savedCamera=value;
      options.onCamera?.(value);
    }
  }
  function restoreCamera(value){
    if(!camera||!value)return;
    camera.position.fromArray(value.position);controls.target.fromArray(value.target);
    camera.near=value.near;camera.far=value.far;camera.updateProjectionMatrix();controls.update();
  }
  function suspend(){
    saveCamera();clearTimeout(cameraTimer);generation++;cancelFrame();
    mixer?.stopAllAction();controls?.dispose();releaseRoot(root);releaseRoot(grid);releaseRoot(markers);
    root=null;grid=null;markers=null;mixer=null;
    renderer?.dispose();renderer?.forceContextLoss();renderer?.domElement.remove();renderer=null;
    delete element.dataset.loaded;
  }
  const resize=()=>{if(!renderer)return;const w=Math.max(element.clientWidth,1),h=Math.max(element.clientHeight,1);renderer.setSize(w,h,false);camera.aspect=w/h;camera.updateProjectionMatrix();};
  function fit(){if(!root)return;const b=new THREE.Box3().setFromObject(root),center=b.getCenter(new THREE.Vector3());camera.position.copy(center).add(new THREE.Vector3(1.1,-1.5,1.1).normalize().multiplyScalar(radius*3));camera.near=radius/1000;camera.far=radius*100;controls.target.copy(center);camera.updateProjectionMatrix();controls.update();saveCamera();}
  function makeGrid(bounds){
    const step=10**Math.floor(Math.log10(radius));
    grid=new THREE.GridHelper(step*20,20,0x8faaa2,0xc6d0c9);
    grid.rotation.x=Math.PI/2; // Z-up model: grid lies in XY, just below the floor.
    const center=bounds.getCenter(new THREE.Vector3());
    grid.position.set(Math.round(center.x/step)*step,Math.round(center.y/step)*step,bounds.min.z-radius*.0005);
    grid.visible=gridVisible;scene.add(grid);
  }
  function drawMeasurement(){
    if(!scene||!root)return;
    if(markers){scene.remove(markers);releaseRoot(markers);}
    markers=new THREE.Group();
    const vertices=points.map(p=>new THREE.Vector3().fromArray(p));
    for(const p of vertices){
      const marker=new THREE.Mesh(new THREE.SphereGeometry(radius*.008,12,8),new THREE.MeshBasicMaterial({color:0xc14c30,depthTest:false}));
      marker.position.copy(p);marker.renderOrder=20;markers.add(marker);
    }
    if(vertices.length===2){
      const line=new THREE.Line(new THREE.BufferGeometry().setFromPoints(vertices),new THREE.LineBasicMaterial({color:0xc14c30,depthTest:false}));
      line.renderOrder=20;markers.add(line);
    }
    scene.add(markers);
  }
  function pick(event){
    if(!measuring||!root||event.button!==0||!pointerStart)return;
    const start=pointerStart;pointerStart=null;
    if(Math.hypot(event.clientX-start[0],event.clientY-start[1])>4)return;
    const rect=renderer.domElement.getBoundingClientRect();
    raycaster.setFromCamera(new THREE.Vector2(2*(event.clientX-rect.left)/rect.width-1,1-2*(event.clientY-rect.top)/rect.height),camera);
    root.updateMatrixWorld(true);
    const hit=raycaster.intersectObject(root,true).find(h=>h.object.isMesh);
    if(hit){
      points=[...(points.length===1?points:[]),hit.point.toArray()];
      drawMeasurement();options.onMeasure?.(points.map(p=>p.slice()));
    }
  }
  async function resume(){
    if(renderer||disposed)return;
    const current=++generation;
    renderer=new THREE.WebGLRenderer({antialias:true,alpha:true});renderer.setPixelRatio(Math.min(devicePixelRatio,2));renderer.outputColorSpace=THREE.SRGBColorSpace;renderer.setClearColor(0xf4f6f3,1);element.appendChild(renderer.domElement);
    scene=new THREE.Scene();camera=new THREE.PerspectiveCamera(35,1,.01,1000);camera.up.set(0,0,1);controls=new OrbitControls(camera,renderer.domElement);controls.enableDamping=true;
    controls.addEventListener('change',()=>{clearTimeout(cameraTimer);cameraTimer=setTimeout(saveCamera,180);});
    renderer.domElement.addEventListener('pointerdown',e=>{pointerStart=[e.clientX,e.clientY];});
    renderer.domElement.addEventListener('pointerup',pick);
    renderer.domElement.addEventListener('pointercancel',()=>{pointerStart=null;});
    renderer.domElement.style.cursor=measuring?'crosshair':'';
    const fill=new THREE.HemisphereLight(0xffffff,0x658078,2),light=new THREE.DirectionalLight(0xffffff,2);
    light.position.set(30,-40,50);scene.add(fill,light);resize();render();
    try{const gltf=await loader.loadAsync(url);if(disposed||current!==generation){releaseRoot(gltf.scene);return;}root=gltf.scene;root.updateMatrixWorld(true);scene.add(root);meshCount=0;root.traverse(o=>{if(o.isMesh){meshCount++;for(const m of(Array.isArray(o.material)?o.material:[o.material]))m.wireframe=wire;}});clips=gltf.animations.length;mixer=clips?new THREE.AnimationMixer(root):null;gltf.animations.forEach(c=>mixer.clipAction(c).play());
      const bounds=new THREE.Box3().setFromObject(root);radius=Math.max(bounds.getSize(new THREE.Vector3()).length()/2,.01);
      authoredLights=0;root.traverse(o=>{if(o.isLight)authoredLights++;});
      authoredCamera=!!gltf.cameras[0];
      if(authoredCamera)camera.fov=gltf.cameras[0].fov;
      if(authoredLights){
        // Authored scene lights should not be overwhelmed by the model-viewer fill.
        fill.visible=false;light.visible=false;renderer.useLegacyLights=false;
        renderer.toneMapping=THREE.ACESFilmicToneMapping;renderer.toneMappingExposure=.85;
        renderer.setClearColor(0x071329,1);
      }
      makeGrid(bounds);
      if(savedCamera)restoreCamera(savedCamera);
      else if(authoredCamera){
        const shot=gltf.cameras[0],direction=shot.getWorldDirection(new THREE.Vector3());
        shot.getWorldPosition(camera.position);shot.getWorldQuaternion(camera.quaternion);
        camera.fov=shot.fov;camera.near=shot.near;camera.far=shot.far;
        const distance=Math.max(camera.near*2,bounds.getCenter(new THREE.Vector3()).sub(camera.position).dot(direction));
        controls.target.copy(camera.position).addScaledVector(direction,distance);
        camera.updateProjectionMatrix();controls.update();saveCamera();
      }else fit();drawMeasurement();
      element.dataset.loaded='true';element.dataset.meshes=String(meshCount);element.dataset.animations=String(clips);}
    catch(e){element.dataset.error=e.message;element.dispatchEvent(new CustomEvent('viewer-error',{detail:e.message,bubbles:true}));}
  }
  function observe(){
    observer?.disconnect();resizeObserver?.disconnect();
    const win=element.ownerDocument.defaultView;
    resizeObserver=new win.ResizeObserver(resize);resizeObserver.observe(element);
    const nextObserver=new win.IntersectionObserver(entries=>{
      if(observer!==nextObserver||element.ownerDocument.defaultView!==win)return;
      visible=entries[0].isIntersecting;if(visible)resume();else suspend();
    },{rootMargin:'100px'});
    observer=nextObserver;observer.observe(element);
  }
  function relocate(){
    if(disposed)return;
    cancelFrame();
    if(controls){
      const value=cameraState();controls.dispose();
      controls=new OrbitControls(camera,renderer.domElement);controls.enableDamping=true;
      restoreCamera(value);
      controls.addEventListener('change',()=>{clearTimeout(cameraTimer);cameraTimer=setTimeout(saveCamera,180);});
    }
    observe();resize();visible=true;
    if(renderer)render();else resume();
  }
  observe();
  return {fit,relocate,setWireframe(value){wire=value;root?.traverse(o=>{if(o.isMesh)for(const m of(Array.isArray(o.material)?o.material:[o.material]))m.wireframe=wire;});},setPaused(value){paused=value;},
    setGrid(value){gridVisible=value;if(grid)grid.visible=value;},
    setCamera(value){if(value&&JSON.stringify(value)!==JSON.stringify(savedCamera)){savedCamera=value;restoreCamera(value);}},
    setMeasuring(value){measuring=value;if(renderer)renderer.domElement.style.cursor=value?'crosshair':'';},
    setPoints(value){if(JSON.stringify(points)!==JSON.stringify(value)){points=value;drawMeasurement();}},
    inspect(){return {meshCount,clips,authoredLights,authoredCamera,paused,wire,grid:gridVisible,measuring,points,camera:cameraState(),positions:root?root.children.map(o=>o.matrixWorld.elements.slice()):[]};},
    dispose(){disposed=true;observer.disconnect();resizeObserver.disconnect();suspend();}};
}

export function sectionSvg(element, polygons){
  const points=polygons.flat();if(!points.length){element.textContent='Empty cross-section';return;}
  const xs=points.map(p=>p[0]),ys=points.map(p=>p[1]);const minX=Math.min(...xs),maxX=Math.max(...xs),minY=Math.min(...ys),maxY=Math.max(...ys),pad=Math.max(maxX-minX,maxY-minY,.01)*.08;
  const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg'),path=document.createElementNS(ns,'path');
  svg.setAttribute('viewBox',`${minX-pad} ${-maxY-pad} ${maxX-minX+2*pad} ${maxY-minY+2*pad}`);svg.setAttribute('role','img');svg.setAttribute('aria-label','Cross-section');
  path.setAttribute('d',polygons.map(p=>p.map(([x,y],i)=>`${i?'L':'M'}${x} ${-y}`).join(' ')+' Z').join(' '));path.setAttribute('fill-rule','evenodd');path.setAttribute('fill','#498c86');svg.appendChild(path);element.replaceChildren(svg);
}
