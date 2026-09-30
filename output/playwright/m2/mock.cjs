async (page) => {
  let rows = [
    {id:'11111111-1111-4111-8111-111111111111',content:'生日是3月15日',memoryType:'fact',source:'explicit',confidence:'high',status:'active',neverDecay:false,correctionCount:0,embeddingReady:false},
    {id:'22222222-2222-4222-8222-222222222222',content:'可能偏好绿茶',memoryType:'preference',source:'inferred',confidence:'low',status:'pending',neverDecay:false,correctionCount:0,embeddingReady:false}
  ];
  await page.route('**/api/v1/**', async route => {
    const req=route.request(), url=new URL(req.url()), id=url.pathname.split('/').at(-1);
    const body=req.postData() ? req.postDataJSON() : {};
    let result={};
    if(req.method()==='PATCH') {
      rows=rows.map(r=>r.id===id?{...r,...body,correctionCount:r.correctionCount+1}:r);
      result=rows.find(r=>r.id===id);
    } else if(req.method()==='DELETE') {
      rows=id==='memory'?[]:rows.filter(r=>r.id!==id); result={deleted:true};
    } else if(id==='confirm') {
      rows=rows.map(r=>body.ids.includes(r.id)?{...r,status:'active',source:'explicit',confidence:'high'}:r);
      result={confirmed:true};
    } else if(id==='memory') {
      const found=rows.filter(r=>(!url.searchParams.get('status')||r.status===url.searchParams.get('status'))&&(!url.searchParams.get('q')||r.content.includes(url.searchParams.get('q'))));
      result={items:found,total:found.length,offset:0,limit:20};
    } else result=rows.find(r=>r.id===id)||[];
    await route.fulfill({json:result});
  });
  await page.addInitScript(()=>localStorage.setItem('luoye_token','isolated-ui-fixture'));
  await page.reload();
}
