import * as THREE from 'three';
import {OrbitControls} from '/vendor/three/examples/jsm/controls/OrbitControls.js';
import {GLTFLoader} from '/vendor/three/examples/jsm/loaders/GLTFLoader.js';

export function createViewer(element) {
  const renderer = new THREE.WebGLRenderer({antialias: true, preserveDrawingBuffer: true});
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  renderer.setClearColor(0xedf0ed);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.25;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  element.appendChild(renderer.domElement);

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(35, 1, 0.1, 2000);
  camera.up.set(0, 0, 1);
  const controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true;
  controls.dampingFactor = 0.09;
  const sky = new THREE.HemisphereLight(0xf8f7ee, 0x82937b, 1.5);
  sky.position.set(0, 0, 1);
  scene.add(sky);
  const key = new THREE.DirectionalLight(0xfff6e7, 2.3);
  key.position.set(60, -80, 120);
  key.castShadow = true;
  key.shadow.mapSize.set(2048, 2048);
  key.shadow.bias = -0.00015;
  key.shadow.normalBias = 0.05;
  scene.add(key, key.target);
  const fill = new THREE.DirectionalLight(0xdce8ec, 1.1);
  fill.position.set(-50, 35, 60);
  scene.add(fill);
  const grid = new THREE.GridHelper(200, 40, 0xc3cec1, 0xd4dcd1);
  grid.rotation.x = Math.PI / 2;
  grid.material.transparent = true;
  grid.material.opacity = 0.55;
  scene.add(grid);
  const floor = new THREE.Mesh(new THREE.PlaneGeometry(2000, 2000), new THREE.ShadowMaterial({opacity: 0.11}));
  floor.receiveShadow = true;
  scene.add(floor);
  const loader = new GLTFLoader();
  const clock = new THREE.Clock();
  let root = null, mixer = null, clips = [], playing = true, wireframe = false;
  let fitBounds = new THREE.Box3(new THREE.Vector3(-15, -15, 0), new THREE.Vector3(15, 15, 40));
  let request = 0;

  function fit() {
    const center = fitBounds.getCenter(new THREE.Vector3());
    const radius = Math.max(fitBounds.getSize(new THREE.Vector3()).length() / 2, 1);
    const fov = Math.min(camera.fov * Math.PI / 180, 2 * Math.atan(Math.tan(camera.fov * Math.PI / 360) * camera.aspect));
    const distance = radius / Math.sin(fov / 2) * 1.18;
    camera.position.copy(center).add(new THREE.Vector3(1.2, -1.75, 1.05).normalize().multiplyScalar(distance));
    camera.near = Math.max(radius / 1000, 0.001);
    camera.far = radius * 100;
    camera.updateProjectionMatrix();
    controls.target.copy(center);
    controls.minDistance = radius * 0.1;
    controls.maxDistance = radius * 25;
    controls.update();
  }
  function disposeRoot(object) {
    const geometries = new Set(), materials = new Set(), textures = new Set();
    object.traverse(child => {
      if (child.geometry) geometries.add(child.geometry);
      for (const material of (Array.isArray(child.material) ? child.material : [child.material])) {
        if (!material) continue;
        materials.add(material);
        for (const value of Object.values(material)) if (value?.isTexture) textures.add(value);
      }
    });
    for (const geometry of geometries) geometry.dispose();
    for (const material of materials) material.dispose();
    for (const texture of textures) { texture.source?.data?.close?.(); texture.dispose(); }
  }
  async function load(buffer) {
    const id = ++request;
    const gltf = await loader.parseAsync(buffer, '');
    if (id !== request) { disposeRoot(gltf.scene); return null; }
    const next = gltf.scene;
    next.updateMatrixWorld(true);
    const bounds = new THREE.Box3().setFromObject(next);
    if (bounds.isEmpty()) { disposeRoot(next); throw new Error('The GLB contains no visible geometry.'); }
    next.traverse(child => {
      if (!child.isMesh) return;
      if (!child.geometry.attributes.normal) child.geometry.computeVertexNormals();
      child.castShadow = true;
      child.receiveShadow = true;
      for (const material of (Array.isArray(child.material) ? child.material : [child.material])) material.wireframe = wireframe;
    });
    if (root) {
      if (mixer) { mixer.stopAllAction(); mixer.uncacheRoot(root); }
      scene.remove(root);
      disposeRoot(root);
    }
    root = next; clips = gltf.animations;
    scene.add(root);
    fitBounds = bounds;
    const size = Math.max(bounds.getSize(new THREE.Vector3()).length(), 1);
    const center = bounds.getCenter(new THREE.Vector3());
    grid.position.set(center.x, center.y, bounds.min.z - size * 0.004);
    grid.scale.setScalar(size / 65);
    floor.position.z = grid.position.z - 0.01;
    key.position.copy(center).add(new THREE.Vector3(size, -size, size * 2));
    key.target.position.copy(center);
    Object.assign(key.shadow.camera, {left: -size, right: size, top: size, bottom: -size, near: 0.1, far: size * 6});
    key.shadow.camera.updateProjectionMatrix();
    mixer = clips.length ? new THREE.AnimationMixer(root) : null;
    if (mixer) for (const clip of clips) mixer.clipAction(clip).play();
    playing = true;
    fit();
    renderer.render(scene, camera);
    return {animations: clips.length};
  }
  const observer = new ResizeObserver(() => {
    const width = element.clientWidth, height = element.clientHeight;
    renderer.setSize(width, height);
    camera.aspect = width / Math.max(height, 1);
    camera.updateProjectionMatrix();
  });
  observer.observe(element);
  fit();
  renderer.setAnimationLoop(() => {
    const delta = Math.min(clock.getDelta(), 0.1);
    if (mixer && playing) mixer.update(delta);
    controls.update();
    renderer.render(scene, camera);
  });
  return {
    load, fit,
    cancelLoad() { ++request; },
    setWireframe(value) {
      wireframe = value;
      root?.traverse(child => {
        if (child.isMesh) for (const material of (Array.isArray(child.material) ? child.material : [child.material])) material.wireframe = value;
      });
    },
    setGrid(value) { grid.visible = value; },
    setPlaying(value) { playing = value; },
    inspect() {
      const meshes = [];
      root?.updateMatrixWorld(true);
      root?.traverse(child => { if (child.isMesh) meshes.push({vertices: child.geometry.attributes.position.count, matrix: child.matrixWorld.toArray()}); });
      return {meshes, animations: clips.length, playing, wireframe};
    },
    dispose() {
      ++request;
      renderer.setAnimationLoop(null); observer.disconnect(); controls.dispose();
      if (root) disposeRoot(root);
      grid.geometry.dispose(); grid.material.dispose(); floor.geometry.dispose(); floor.material.dispose(); renderer.dispose();
    }
  };
}
