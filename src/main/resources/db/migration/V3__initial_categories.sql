INSERT INTO records(id,kind,data)
SELECT ('10000000-0000-4000-8000-' || lpad(ordinality::text,12,'0'))::uuid,
       'categories', jsonb_build_object('name',name,'active',true,'systemKey',lower(name))
FROM unnest(ARRAY['Service','Repairs','Tyres','Insurance','Tracker','Cleaning','Inspection','Parking','Accessories','Other']) WITH ORDINALITY AS categories(name,ordinality);
